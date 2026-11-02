package dev.seedgenglobe;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import dev.seedgenglobe.command.GlobeCommands;
import dev.seedgenglobe.terrain.GlobeTerrain;

/**
 * SeedGenGlobe: a seed-based Distant Horizons generator for Big Globe worlds.
 * <p>
 * Distant Horizons normally builds distant terrain by generating real chunks. For Big Globe that means its full
 * chunk pipeline (terrain, structures, features, lighting) for every chunk in view. SeedGenGlobe instead computes
 * LOD columns directly from Big Globe's own column scripts at whatever spacing Distant Horizons asks for.
 */
public final class SeedGenGlobe implements ModInitializer {

	public static final String MOD_ID = "seedgenglobe";
	public static final Logger LOGGER = Log.LOGGER;

	private static final AtomicInteger COLUMN_ERRORS_LOGGED = new AtomicInteger();

	@Override
	public void onInitialize() {
		GlobeConfig config = GlobeConfig.load();
		ModCompat.logDetected();
		if (!config.enabled) {
			LOGGER.info("SeedGenGlobe is disabled in {}.", GlobeConfig.FILE_NAME);
		}
		else if (!ModCompat.DISTANT_HORIZONS) {
			LOGGER.warn("Distant Horizons is not installed; SeedGenGlobe has nothing to generate for.");
		}
		else {
			try {
				dev.seedgenglobe.dh.DhBridge.register();
			}
			catch (LinkageError error) {
				LOGGER.error("SeedGenGlobe does not support this Distant Horizons version and will stay idle.", error);
			}
		}
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> GlobeCommands.register(dispatcher));
	}

	/** Logs the first few column failures in full and then only counts them, so a broken data pack cannot flood the log. */
	public static void logColumnError(GlobeTerrain terrain, int x, int z, Throwable throwable) {
		int logged = COLUMN_ERRORS_LOGGED.incrementAndGet();
		if (logged <= 5) {
			LOGGER.error("SeedGenGlobe could not compute the {} column at x={} z={}; it is left empty.", terrain.dimension, x, z, throwable);
		}
		else if (logged == 6) {
			LOGGER.error("SeedGenGlobe: further column errors are counted but no longer logged (see /seedgenglobe status).");
		}
	}
}
