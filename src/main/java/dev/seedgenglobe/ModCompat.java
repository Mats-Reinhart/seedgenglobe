package dev.seedgenglobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Which of the mods SeedGenGlobe cares about are installed, and what it does about each.
 * Every check is by mod id only, so none of these mods are needed at compile time or at runtime.
 */
public final class ModCompat {

	public static final boolean DISTANT_HORIZONS = loaded("distanthorizons");
	public static final boolean BIG_GLOBE = loaded("bigglobe");
	public static final boolean VERTIGO = loaded("vertigo");
	public static final boolean IRIS = loaded("iris");
	public static final boolean SODIUM = loaded("sodium");
	public static final boolean C2ME = loaded("c2me");
	public static final boolean NOISIUM = loaded("noisium");
	public static final boolean LITHIUM = loaded("lithium");
	public static final boolean SCALABLELUX = loaded("scalablelux");
	public static final boolean SEEDGEN = loaded("seedrenderer");
	public static final boolean VOXY = loaded("voxy");

	private ModCompat() {}

	public static boolean loaded(String id) {
		try {
			return FabricLoader.getInstance().isModLoaded(id);
		}
		catch (Throwable ignored) {
			return false;
		}
	}

	public static String version(String id) {
		try {
			Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(id);
			return container.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("absent");
		}
		catch (Throwable ignored) {
			return "unknown";
		}
	}

	/** One line per detected companion mod, saying how SeedGenGlobe treats it. Logged at startup and shown by the status command. */
	public static List<String> describe() {
		List<String> lines = new ArrayList<>();
		if (DISTANT_HORIZONS) lines.add("Distant Horizons " + version("distanthorizons") + ": SeedGenGlobe registers as its world generator for Big Globe dimensions.");
		if (BIG_GLOBE) lines.add("Big Globe " + version("bigglobe") + ": terrain is computed by Big Globe's own column scripts, so LODs match real chunks.");
		if (VERTIGO) lines.add("Vertigo: chunks whose top or bottom sections were not synced are kept from overwriting LODs (vertigoGuard).");
		if (C2ME) lines.add("C2ME: LOD columns are computed on Distant Horizons' threads only, never on Big Globe's chunk worker pool that C2ME drives.");
		if (SCALABLELUX) lines.add("ScalableLux: LOD sky light is computed from Big Globe's column data, so no light engine work is queued for distant terrain.");
		if (NOISIUM) lines.add("Noisium: no interaction. Big Globe terrain does not use the vanilla noise router that Noisium optimises.");
		if (LITHIUM) lines.add("Lithium: no interaction. SeedGenGlobe touches no game logic that Lithium changes.");
		if (SODIUM) lines.add("Sodium: no interaction. Distant Horizons renders LODs itself.");
		if (IRIS) lines.add("Iris: LODs carry real block states and biomes, so shader packs see correct DH materials (leaves, water, snow).");
		if (SEEDGEN) lines.add("Distant Horizons SeedGen: it stands down for Big Globe dimensions, SeedGenGlobe takes them; its queue tuning still applies.");
		if (VOXY) lines.add("Voxy: not supported by SeedGenGlobe; Voxy keeps its own pipeline.");
		return lines;
	}

	public static void logDetected() {
		for (String line : describe()) {
			Log.LOGGER.info("SeedGenGlobe compat: {}", line);
		}
	}
}
