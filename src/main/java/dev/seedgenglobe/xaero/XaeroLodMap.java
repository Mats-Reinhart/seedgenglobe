package dev.seedgenglobe.xaero;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import xaero.map.MapProcessor;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.client.ClientGeneration;
import dev.seedgenglobe.dh.DhBridge;
import dev.seedgenglobe.dh.GlobeWorldGenerator;
import dev.seedgenglobe.terrain.CanopyModel;

/**
 * Xaero's World Map and Minimap integration: the LOD surface map of the whole Distant Horizons radius.
 * Loaded only when Xaero's World Map is installed.
 */
public final class XaeroLodMap {

	/**
	 * Version of what the LOD map stores per column; part of its folder name, so a SeedGenGlobe update that changes
	 * how columns are read starts a fresh map instead of mixing old and new regions.
	 */
	static final int MAP_VERSION = 4;

	private static @Nullable LodMapBuilder builder;
	private static @Nullable XaeroLodWriter writer;
	private static final Map<Path, LodMapStore> STORES = new ConcurrentHashMap<>();
	private static int failures;
	private static boolean disabled;

	private XaeroLodMap() {}

	public static void init() {
		if (builder != null) return;
		builder = new LodMapBuilder();
		writer = new XaeroLodWriter(builder);
		SeedGenGlobe.LOGGER.info("SeedGenGlobe: Xaero's World Map detected, it gets the LOD surface map of Big Globe dimensions.");
	}

	private static long loggedAt = System.nanoTime(), loggedProgress;

	/** Client thread, every tick. */
	public static void tick(Minecraft minecraft) {
		XaeroLodWriter writer = XaeroLodMap.writer;
		if (writer == null || disabled) return;
		if (!GlobeConfig.get().xaeroLodMap) {
			// switched off in the settings: stop computing and writing (what is in Xaero's map stays until it maps over it)
			if (writer.target() != null) writer.reset();
			return;
		}
		try {
			writer.tick(minecraft);
			logProgress(writer);
		}
		catch (Throwable throwable) {
			if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
			failures++;
			SeedGenGlobe.LOGGER.error("SeedGenGlobe: writing the LOD map into Xaero's map failed ({} of 3 times allowed).", failures, throwable);
			try {
				writer.reset();
			}
			catch (Throwable ignored) {}
			if (failures >= 3) {
				disabled = true;
				SeedGenGlobe.LOGGER.error("SeedGenGlobe: the Xaero LOD map is off for the rest of this session. Distant Horizons generation is not affected.");
			}
		}
	}

	/** A line in the log every {@code statsLogSeconds} while the map is growing, like the generation summaries. */
	private static void logProgress(XaeroLodWriter writer) {
		int seconds = GlobeConfig.get().statsLogSeconds;
		LodMapBuilder builder = XaeroLodMap.builder;
		if (seconds <= 0 || builder == null || writer.target() == null) return;
		long now = System.nanoTime();
		if (now - loggedAt < seconds * 1_000_000_000L) return;
		loggedAt = now;
		long progress = builder.liveSections.get() + builder.savedRegions.get() + writer.filledTiles;
		if (progress == loggedProgress) return;
		loggedProgress = progress;
		List<String> lines = new ArrayList<>(builder.statusLines());
		lines.addAll(writer.statusLines());
		SeedGenGlobe.LOGGER.info("SeedGenGlobe: {}", String.join("; ", lines));
	}

	public static void onDisconnect() {
		XaeroLodWriter writer = XaeroLodMap.writer;
		if (writer != null) {
			try {
				writer.reset();
			}
			catch (Throwable throwable) {
				SeedGenGlobe.LOGGER.debug("SeedGenGlobe: Xaero LOD map reset failed", throwable);
			}
		}
	}

	/** The map builder (for the progress overlay), or null when Xaero's World Map is not installed. */
	public static @Nullable LodMapBuilder builder() {
		return builder;
	}

	/** Whether the LOD map is being written into Xaero's map right now. */
	public static boolean active() {
		XaeroLodWriter writer = XaeroLodMap.writer;
		return writer != null && !disabled && writer.target() != null;
	}

	public static List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		XaeroLodWriter writer = XaeroLodMap.writer;
		LodMapBuilder builder = XaeroLodMap.builder;
		if (writer == null || builder == null) return lines;
		if (disabled) lines.add("Xaero LOD map: switched off after errors (see the log)");
		lines.addAll(writer.statusLines());
		lines.addAll(builder.statusLines());
		lines.add(XaeroVertigoGuard.status());
		return lines;
	}

	/** The radius in blocks: SeedGenGlobe's setting, or Distant Horizons' render distance. */
	static int radiusBlocks() {
		int chunks = GlobeConfig.get().xaeroLodMapRadius;
		if (chunks <= 0) {
			try {
				Integer value = DhApi.Delayed.configs != null ? DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() : null;
				chunks = value != null && value > 0 ? value : 256;
			}
			catch (RuntimeException | LinkageError error) {
				chunks = 256;
			}
		}
		return Math.max(chunks, 8) * 16;
	}

	/**
	 * Finds the terrain the LOD map of this client level is computed from: the singleplayer server's generator for
	 * the same dimension, or this client's own multiplayer generator.
	 */
	static @Nullable LodMapBuilder.Target builderTarget(ClientLevel level, MapProcessor processor) {
		GlobeWorldGenerator generator = null;
		if (Minecraft.getInstance().hasSingleplayerServer()) {
			String dimension = level.dimension().identifier().toString();
			for (GlobeWorldGenerator candidate : DhBridge.serverGenerators()) {
				if (!candidate.isClosed() && candidate.terrain.dimension.equals(dimension)) {
					generator = candidate;
					break;
				}
			}
		}
		else {
			generator = ClientGeneration.generatorFor(level);
		}
		if (generator == null || generator.isClosed()) return null;
		// only dimensions that look like Big Globe's overworld: ground everywhere, open sky
		if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) return null;
		boolean flowers = processor.isFlowersConfig();
		LodMapStore store = store(generator, level, flowers);
		if (store == null) return null;
		CanopyModel.Shared canopy = generator.canopy();
		return new LodMapBuilder.Target(
			store,
			generator,
			generator.terrain,
			canopy,
			generator.groundCover(),
			generator.structures(),
			generator.levelMinY(),
			generator.levelMaxY(),
			generator.topSkyLight(),
			radiusBlocks(),
			new XaeroRules(processor.getMapWriter(), level, processor.getWorldBlockRegistry(), flowers)
		);
	}

	/**
	 * One store per terrain: the folder name hashes Big Globe's column seed (the same on servers and on clients,
	 * which never learn the world seed) together with the Big Globe version, since a different version may shape
	 * terrain differently, and {@link #MAP_VERSION}. Whether Xaero draws flowers changes which block a column shows,
	 * so both settings get a folder of their own, and so do maps with and without structures (multiplayer clients
	 * cannot place them) and with structures up to another detail level.
	 */
	static @Nullable LodMapStore store(GlobeWorldGenerator generator, ClientLevel level, boolean flowers) {
		long seed = generator.terrain.worldInfo.seed();
		String version = ModCompat.version("bigglobe") + "/" + MAP_VERSION;
		long hash = seed * 0x9E3779B97F4A7C15L ^ version.hashCode() * 0xC2B2AE3D27D4EB4FL;
		hash ^= hash >>> 29;
		String dimension = level.dimension().identifier().toString().replace(':', '_').replace('/', '_')
			+ (flowers ? "" : "-noflowers")
			+ (generator.structures() != null ? "-s" + generator.structures().fullDetail() : "-nostructures");
		Path directory = FabricLoader.getInstance().getGameDir().resolve("seedgenglobe").resolve("lodmap").resolve(Long.toHexString(hash)).resolve(dimension);
		LodMapStore existing = STORES.get(directory);
		if (existing != null) return existing;
		try {
			LodMapStore store = LodMapStore.open(directory, LodMapBuilder.storeCacheCells());
			STORES.put(directory, store);
			return store;
		}
		catch (IOException | RuntimeException exception) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: cannot use {} for the LOD map: {}", directory, exception.toString());
			return null;
		}
	}
}
