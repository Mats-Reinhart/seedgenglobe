package dev.seedgenglobe.dh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import builderb0y.bigglobe.chunkgen.BigGlobeScriptedChunkGenerator;
import builderb0y.bigglobe.features.FlowerControllerFeature;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.structures.LodStructures;
import dev.seedgenglobe.terrain.GlobeTerrain;
import dev.seedgenglobe.terrain.GroundCover;

/**
 * Hooks SeedGenGlobe into Distant Horizons through its public event and override API.
 * Kept apart from the mod entrypoint so no Distant Horizons class is loaded when DH is absent.
 */
public final class DhBridge {

	/**
	 * The open server-side generators. A generator leaves this map when it closes, however that happens (Distant
	 * Horizons closing it, the level unload event, the server stopping): in 1.2.0 the unload event did not find
	 * its generator, so every rejoin kept the old ones, with their level and structure caches, in memory.
	 */
	static final Map<IDhApiLevelWrapper, GlobeWorldGenerator> SERVER_GENERATORS = new ConcurrentHashMap<>();

	private DhBridge() {}

	public static void register() {
		DhApiResult<Void> load = DhApiEventRegister.on(DhApiLevelLoadEvent.class, new LoadHandler());
		DhApiResult<Void> unload = DhApiEventRegister.on(DhApiLevelUnloadEvent.class, new UnloadHandler());
		if (!load.success) SeedGenGlobe.LOGGER.error("SeedGenGlobe could not listen for Distant Horizons level loads: {}", load.message);
		if (!unload.success) SeedGenGlobe.LOGGER.warn("SeedGenGlobe could not listen for Distant Horizons level unloads: {}", unload.message);
		dev.seedgenglobe.gen.GenProgress.DhPipelineView.set(() -> DhPipeline.describe(true));
		SeedGenGlobe.LOGGER.info("SeedGenGlobe is bound to Distant Horizons {} (API {}.{}.{}).", DhApi.getModVersion(), DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), DhApi.getApiPatchVersion());
	}

	/**
	 * The server is stopping: stop every generator and wait for their sections to stop, while Big Globe's registries
	 * are still there. Sections that kept running past this point failed with "Registries not available" in 1.2.0.
	 */
	public static void onServerStopping() {
		List<GlobeWorldGenerator> generators = serverGenerators();
		for (GlobeWorldGenerator generator : generators) generator.shutdown();
		long deadline = System.currentTimeMillis() + 10_000L;
		for (GlobeWorldGenerator generator : generators) {
			long left = deadline - System.currentTimeMillis();
			if (left <= 0L || !generator.awaitStopped(left)) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe [{}]: some sections were still running when the server stopped.", generator.progress.name);
			}
		}
		SERVER_GENERATORS.clear();
	}

	/** Distant Horizons' render distance in blocks, or 0 when it cannot be read (a dedicated server). */
	public static int renderDistanceBlocks() {
		try {
			Integer value = DhApi.Delayed.configs != null ? DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() : null;
			return value != null && value > 0 ? value * 16 : 0;
		}
		catch (RuntimeException | LinkageError error) {
			return 0;
		}
	}

	/** Whether SeedGenGlobe generates (on this side) the dimension a Minecraft level belongs to. */
	public static boolean generates(Object minecraftLevel) {
		if (!(minecraftLevel instanceof net.minecraft.world.level.Level level)) return false;
		String dimension = level.dimension().identifier().toString();
		for (GlobeWorldGenerator generator : SERVER_GENERATORS.values()) {
			if (!generator.isClosed() && generator.dimensionId.equals(dimension)) return true;
		}
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT && dev.seedgenglobe.client.ClientGeneration.generates(level);
	}

	public static final class LoadHandler extends DhApiLevelLoadEvent {

		@Override
		public void onLevelLoad(DhApiEventParam<EventParam> event) {
			try {
				DhBridge.onLevelLoad(event.value.levelWrapper);
			}
			catch (Throwable throwable) {
				SeedGenGlobe.LOGGER.error("SeedGenGlobe failed to set up a Distant Horizons level", throwable);
			}
		}
	}

	public static final class UnloadHandler extends DhApiLevelUnloadEvent {

		@Override
		public void onLevelUnload(DhApiEventParam<EventParam> event) {
			try {
				GlobeWorldGenerator generator = SERVER_GENERATORS.get(event.value.levelWrapper);
				if (generator == null) {
					// Distant Horizons may hand over a different wrapper object for the same level
					Object level = event.value.levelWrapper.getWrappedMcObject();
					for (GlobeWorldGenerator candidate : SERVER_GENERATORS.values()) {
						if (candidate.level.getWrappedMcObject() == level) generator = candidate;
					}
				}
				if (generator != null) generator.shutdown();
				if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
					dev.seedgenglobe.client.ClientGeneration.onLevelUnload(event.value.levelWrapper);
				}
			}
			catch (Throwable throwable) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe failed to clean up a Distant Horizons level", throwable);
			}
		}
	}

	static void onLevelLoad(IDhApiLevelWrapper wrapper) {
		if (!GlobeConfig.get().enabled) return;
		Object level = wrapper.getWrappedMcObject();
		if (level instanceof ServerLevel serverLevel) {
			bindServerLevel(wrapper, serverLevel);
		}
		else if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			dev.seedgenglobe.client.ClientGeneration.onLevelLoad(wrapper);
		}
	}

	static void bindServerLevel(IDhApiLevelWrapper wrapper, ServerLevel level) {
		if (!(level.getChunkSource().getGenerator() instanceof BigGlobeScriptedChunkGenerator generator)) {
			return;
		}
		String dimension = level.dimension().identifier().toString();
		GlobeTerrain terrain = GlobeTerrain.fromGenerator(generator, dimension, plains(level));
		if (terrain == null) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: Big Globe has not compiled {} yet, leaving it to Distant Horizons' default generator.", dimension);
			return;
		}
		GroundCover.Shared ground = GlobeConfig.get().groundCover && isOverworldLike(generator)
			? GroundCover.Shared.create(terrain, wrapper.hasSkyLight(), wrapper.hasCeiling(), level.getSeed(), overworldFlowers(level))
			: null;
		LodStructures structures = LodStructures.create(level, generator, dimension);
		GlobeWorldGenerator globe = new GlobeWorldGenerator(wrapper, terrain, dimension, false, wrapper.hasSkyLight(), wrapper.hasCeiling(), dimension, ground, structures);
		// an earlier generator for the same level (a reload): close it, so it stops and lets go of its old level
		for (Map.Entry<IDhApiLevelWrapper, GlobeWorldGenerator> entry : SERVER_GENERATORS.entrySet()) {
			if (entry.getValue().dimensionId.equals(dimension)) entry.getValue().shutdown();
		}
		boolean generate = GlobeConfig.get().dhGeneration;
		if (!generate || register(wrapper, globe)) {
			SERVER_GENERATORS.put(wrapper, globe);
			globe.onClose(() -> {
				SERVER_GENERATORS.remove(wrapper, globe);
				if (generate) unregister(wrapper, globe);
				SeedGenGlobe.LOGGER.info("SeedGenGlobe [{}] closed: {}", globe.progress.name, globe.progress.summary());
			});
			dev.seedgenglobe.gen.SectionScheduler.get().configure();
			if (!generate) {
				SeedGenGlobe.LOGGER.info("SeedGenGlobe: Distant Horizons LODs are off (dhGeneration=false); {} is only used for the Xaero LOD map.", dimension);
				return;
			}
			SeedGenGlobe.LOGGER.info(
				"SeedGenGlobe now generates Distant Horizons LODs for {} from Big Globe's terrain scripts ({}; level y {}..{}; {})",
				dimension,
				terrain,
				globe.levelMinY(),
				globe.levelMaxY(),
				GlobeConfig.get().summary()
			);
			String advice = nSizedAdvice();
			if (advice != null && !nSizedAdviceLogged) {
				nSizedAdviceLogged = true;
				SeedGenGlobe.LOGGER.info("SeedGenGlobe: {}", advice);
			}
		}
	}

	private static volatile boolean nSizedAdviceLogged;

	/** Ground cover follows Big Globe's overworld decorators, so it is only used where those run. */
	static boolean isOverworldLike(BigGlobeScriptedChunkGenerator generator) {
		try {
			return generator.feature_dispatcher != null
				&& generator.feature_dispatcher.normal != null
				&& ((java.util.Optional<?>)(generator.feature_dispatcher.normal.unwrapKey()))
					.map(key -> key instanceof net.minecraft.resources.ResourceKey<?> resourceKey ? resourceKey.identifier().toString() : "")
					.orElse("")
					.startsWith("bigglobe:overworld/");
		}
		catch (RuntimeException | LinkageError error) {
			return false;
		}
	}

	/** Big Globe's overworld flower controller, which decides where flower patches grow. */
	static @Nullable FlowerControllerFeature.Config overworldFlowers(ServerLevel level) {
		try {
			Object feature = level.registryAccess()
				.lookupOrThrow(Registries.CONFIGURED_FEATURE)
				.getValue(Identifier.parse("bigglobe:overworld/surface_flowers"));
			if (feature instanceof ConfiguredFeature<?, ?> configured && configured.config() instanceof FlowerControllerFeature.Config config) return config;
		}
		catch (RuntimeException | LinkageError error) {
			SeedGenGlobe.LOGGER.debug("SeedGenGlobe: no Big Globe flower controller", error);
		}
		return null;
	}

	/**
	 * Players connected to a Distant Horizons server only ask it for full-detail sections unless DH's experimental
	 * "N-sized generation" is on (on the server and on the client), so coarse far sections, where SeedGenGlobe saves
	 * the most, are never requested. Singleplayer and SeedGenGlobe's own client generation are not affected.
	 *
	 * @return advice for a dedicated server where that option is off, otherwise null
	 */
	public static @Nullable String nSizedAdvice() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return null;
		try {
			if (Boolean.TRUE.equals(com.seibel.distanthorizons.core.config.Config.Server.Experimental.enableNSizedGeneration.get())) return null;
		}
		catch (LinkageError | RuntimeException unknown) {
			return null; // a Distant Horizons version without this option
		}
		return "players only request full-detail LOD sections from this server because Distant Horizons' experimental N-sized generation is off. "
			+ "Turn it on (Distant Horizons config: server > experimental > enableNSizedGeneration, command name generation.nSized) on the server and on clients "
			+ "so distant terrain is requested as coarse sections, which SeedGenGlobe generates directly.";
	}

	/** Registers the generator, retrying with another priority if a different mod already took ours. */
	public static boolean register(IDhApiLevelWrapper wrapper, GlobeWorldGenerator generator) {
		DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(wrapper, generator);
		if (!result.success) {
			SeedGenGlobe.LOGGER.error(
				"SeedGenGlobe could not register its generator for {}: {}. If another mod uses override priority {}, change generatorPriority in {}.",
				generator.progress.name,
				result.message,
				generator.getPriority(),
				GlobeConfig.FILE_NAME
			);
			return false;
		}
		return true;
	}

	/**
	 * Takes a generator that was shut down back out of Distant Horizons' registry ({@code WorldGeneratorInjectorMixin}),
	 * so neither it nor the stopped level it was registered for stays in memory for the rest of the game session.
	 */
	static void unregister(IDhApiLevelWrapper wrapper, GlobeWorldGenerator generator) {
		try {
			if ((Object)(WorldGeneratorInjector.INSTANCE) instanceof DhInjectorHook injector && !injector.seedgenglobe$unbind(wrapper, generator)) {
				SeedGenGlobe.LOGGER.debug("SeedGenGlobe [{}]: the generator was no longer registered with Distant Horizons", generator.progress.name);
			}
		}
		catch (RuntimeException | LinkageError error) {
			SeedGenGlobe.LOGGER.debug("SeedGenGlobe [{}]: could not unregister the generator from Distant Horizons", generator.progress.name, error);
		}
	}

	static @Nullable Holder<Biome> plains(ServerLevel level) {
		try {
			return level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	public static List<GlobeWorldGenerator> serverGenerators() {
		return new ArrayList<>(SERVER_GENERATORS.values());
	}
}
