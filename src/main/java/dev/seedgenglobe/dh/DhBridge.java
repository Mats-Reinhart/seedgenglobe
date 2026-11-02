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

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import builderb0y.bigglobe.chunkgen.BigGlobeScriptedChunkGenerator;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.terrain.GlobeTerrain;

/**
 * Hooks SeedGenGlobe into Distant Horizons through its public event and override API.
 * Kept apart from the mod entrypoint so no Distant Horizons class is loaded when DH is absent.
 */
public final class DhBridge {

	static final Map<IDhApiLevelWrapper, GlobeWorldGenerator> SERVER_GENERATORS = new ConcurrentHashMap<>();

	private DhBridge() {}

	public static void register() {
		DhApiResult<Void> load = DhApiEventRegister.on(DhApiLevelLoadEvent.class, new LoadHandler());
		DhApiResult<Void> unload = DhApiEventRegister.on(DhApiLevelUnloadEvent.class, new UnloadHandler());
		if (!load.success) SeedGenGlobe.LOGGER.error("SeedGenGlobe could not listen for Distant Horizons level loads: {}", load.message);
		if (!unload.success) SeedGenGlobe.LOGGER.warn("SeedGenGlobe could not listen for Distant Horizons level unloads: {}", unload.message);
		SeedGenGlobe.LOGGER.info("SeedGenGlobe is bound to Distant Horizons {} (API {}.{}.{}).", DhApi.getModVersion(), DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), DhApi.getApiPatchVersion());
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
				GlobeWorldGenerator generator = SERVER_GENERATORS.remove(event.value.levelWrapper);
				if (generator != null) {
					SeedGenGlobe.LOGGER.info("SeedGenGlobe [{}] unloaded: {}", generator.stats.name, generator.stats.summary());
					generator.close();
				}
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
		GlobeWorldGenerator globe = new GlobeWorldGenerator(wrapper, terrain, wrapper.hasSkyLight(), wrapper.hasCeiling(), dimension);
		if (register(wrapper, globe)) {
			SERVER_GENERATORS.put(wrapper, globe);
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
				generator.stats.name,
				result.message,
				generator.getPriority(),
				GlobeConfig.FILE_NAME
			);
			return false;
		}
		return true;
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
