package dev.seedgenglobe.xaero;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import xaero.map.MapProcessor;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.LodMapFolders;
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
	/**
	 * LOD regeneration ({@link dev.seedgenglobe.regen.LodRegeneration}): forgets the stores kept for these folders, so
	 * the map starts empty there when the world is opened again, and runs {@code delete} on the builder thread, after
	 * whatever it still had to write there (the index saved when the world was left). Any thread.
	 */
	public static void discard(List<Path> folders, Runnable delete) {
		List<Path> normal = new ArrayList<>();
		for (Path folder : folders) normal.add(folder.toAbsolutePath().normalize());
		STORES.keySet().removeIf(directory -> normal.contains(directory.toAbsolutePath().normalize()));
		LodMapBuilder builder = XaeroLodMap.builder;
		if (builder == null) {
			delete.run();
			return;
		}
		builder.runLater(() -> {
			LodMapBuilder.Target target = builder.target();
			if (target != null && normal.contains(target.store().directory.toAbsolutePath().normalize())) {
				// the world was opened again before this ran: leave the map in use alone
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration: the Xaero LOD map in {} is in use again, it was not cleared.", target.store().directory);
				return;
			}
			delete.run();
		});
	}

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

	/**
	 * The automatic radius stops here (chunks). Filling Xaero's map is much slower than computing it: about 40 regions
	 * of 512 blocks a minute in a singleplayer test, so following a Distant Horizons radius of 4,048 chunks (some
	 * 50,000 regions) kept the map filling, and short of most of them, for as long as the game ran. 512 chunks is about
	 * 800 regions. A radius set by number is used as it is.
	 */
	static final int AUTO_MAX_CHUNKS = 512;

	/** The radius in blocks: SeedGenGlobe's setting, or Distant Horizons' render distance up to {@link #AUTO_MAX_CHUNKS}. */
	static int radiusBlocks() {
		int chunks = GlobeConfig.get().xaeroLodMapRadius;
		if (chunks <= 0) {
			try {
				Integer value = DhApi.Delayed.configs != null ? DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() : null;
				chunks = value != null && value > 0 ? Math.min(value, AUTO_MAX_CHUNKS) : 256;
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
	 * terrain differently, and the map's version (see {@link LodMapFolders#terrain}). Whether Xaero draws flowers
	 * changes which block a column shows, so both settings get a folder of their own, and so do maps with and without
	 * structures (multiplayer clients cannot place them) and with structures up to another detail level.
	 */
	static @Nullable LodMapStore store(GlobeWorldGenerator generator, ClientLevel level, boolean flowers) {
		String dimension = LodMapFolders.dimension(level.dimension().identifier().toString())
			+ (flowers ? "" : "-noflowers")
			+ (generator.structures() != null ? "-s" + generator.structures().fullDetail() : "-nostructures");
		Path directory = LodMapFolders.root().resolve(LodMapFolders.terrain(generator.terrain.worldInfo.seed())).resolve(dimension);
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
