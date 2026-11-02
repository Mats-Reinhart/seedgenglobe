package dev.seedgenglobe;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings for SeedGenGlobe, kept in {@code config/seedgenglobe.properties}.
 * A plain properties file is used on purpose: it needs no extra library and is easy to edit by hand.
 * Unknown or malformed values fall back to their defaults and the file is rewritten with every key present.
 */
public final class GlobeConfig {

	public static final String FILE_NAME = "seedgenglobe.properties";

	private static volatile GlobeConfig current = new GlobeConfig();

	/** Master switch. When false the mod registers nothing and Distant Horizons behaves as if it were absent. */
	public boolean enabled = true;
	/**
	 * Coarsest Distant Horizons data detail level SeedGenGlobe will generate directly (0-15).
	 * At detail n every LOD column is 2^n blocks wide, so far sections are produced in one pass
	 * instead of being built from full-resolution chunks. 15 lets DH ask for any level it renders.
	 */
	public int maxDetailLevel = 15;
	/** How much of the underground to compute: none, fill, carve or decorate (same meaning as Big Globe's own option). */
	public String undergroundMode = "fill";
	/** Sample the centre of every LOD cell instead of its corner. Avoids a half-cell shift at coarse detail levels. */
	public boolean sampleCellCenter = true;
	/** Approximate tree canopies from Big Globe's foliage and wood palette data, so forests stay forests at a distance. */
	public boolean trees = true;
	/** Multiplayer: generate LODs on this client from the terrain scripts Big Globe syncs, when the server has no Distant Horizons. */
	public boolean clientGeneration = true;
	/** Multiplayer: also generate locally when the server runs Distant Horizons (instead of downloading LODs from it). */
	public boolean clientGenerationWithDhServer = false;
	/** Worker threads for client-side generation. 0 picks a value from the CPU count and the other mods that are installed. */
	public int clientThreads = 0;
	/** With Vertigo installed, keep chunks whose top or bottom sections were not synced from overwriting LODs with flat cuts. */
	public boolean vertigoGuard = true;
	/** Tint distant grass, leaves and water with Big Globe's own colour scripts instead of flat per-biome colours. */
	public boolean bigGlobeColors = true;
	/** Override priority used when registering with Distant Horizons. Must differ from every other generator override. */
	public int generatorPriority = 2026;
	/** Log a throughput summary every this many generated sections (0 disables). */
	public int statsInterval = 500;

	public static GlobeConfig get() {
		return current;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static synchronized GlobeConfig load() {
		GlobeConfig config = new GlobeConfig();
		Path path = path();
		if (Files.isRegularFile(path)) {
			Properties properties = new Properties();
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				properties.load(reader);
				config.read(properties);
			}
			catch (IOException | RuntimeException exception) {
				Log.LOGGER.warn("SeedGenGlobe could not read {}, using defaults: {}", path, exception.toString());
			}
		}
		config.clamp();
		try {
			config.save(path);
		}
		catch (IOException exception) {
			Log.LOGGER.warn("SeedGenGlobe could not write {}: {}", path, exception.toString());
		}
		current = config;
		return config;
	}

	private void read(Properties p) {
		this.enabled = bool(p, "enabled", this.enabled);
		this.maxDetailLevel = integer(p, "maxDetailLevel", this.maxDetailLevel);
		this.undergroundMode = p.getProperty("undergroundMode", this.undergroundMode).trim().toLowerCase(Locale.ROOT);
		this.sampleCellCenter = bool(p, "sampleCellCenter", this.sampleCellCenter);
		this.trees = bool(p, "trees", this.trees);
		this.clientGeneration = bool(p, "clientGeneration", this.clientGeneration);
		this.clientGenerationWithDhServer = bool(p, "clientGenerationWithDhServer", this.clientGenerationWithDhServer);
		this.clientThreads = integer(p, "clientThreads", this.clientThreads);
		this.vertigoGuard = bool(p, "vertigoGuard", this.vertigoGuard);
		this.bigGlobeColors = bool(p, "bigGlobeColors", this.bigGlobeColors);
		this.generatorPriority = integer(p, "generatorPriority", this.generatorPriority);
		this.statsInterval = integer(p, "statsInterval", this.statsInterval);
	}

	private void clamp() {
		this.maxDetailLevel = Math.max(0, Math.min(15, this.maxDetailLevel));
		this.clientThreads = Math.max(0, Math.min(64, this.clientThreads));
		this.generatorPriority = Math.max(1, this.generatorPriority);
		this.statsInterval = Math.max(0, this.statsInterval);
		switch (this.undergroundMode) {
			case "none", "fill", "carve", "decorate", "bigglobe" -> {}
			default -> this.undergroundMode = "fill";
		}
	}

	private void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			w.write("# SeedGenGlobe: fast Distant Horizons LODs for Big Globe worlds.\n");
			w.write("# Changes apply the next time a world (or server) is loaded.\n\n");
			entry(w, "enabled", this.enabled, "Master switch.");
			entry(w, "maxDetailLevel", this.maxDetailLevel, "Coarsest DH detail level generated directly (0-15). LOD columns are 2^n blocks wide.");
			entry(w, "undergroundMode", this.undergroundMode, "none | fill | carve | decorate | bigglobe (use Big Globe's own Distant Horizons setting). fill is fastest and looks the same from above.");
			entry(w, "sampleCellCenter", this.sampleCellCenter, "Sample the centre of each LOD cell instead of its corner.");
			entry(w, "trees", this.trees, "Approximate tree canopies so forests remain visible in the distance.");
			entry(w, "clientGeneration", this.clientGeneration, "Multiplayer: generate LODs on the client when the server has no Distant Horizons.");
			entry(w, "clientGenerationWithDhServer", this.clientGenerationWithDhServer, "Multiplayer: generate locally even when the server runs Distant Horizons.");
			entry(w, "clientThreads", this.clientThreads, "Client generation threads, 0 = automatic.");
			entry(w, "vertigoGuard", this.vertigoGuard, "With Vertigo, ignore chunks whose terrain was cut off by vertical syncing.");
			entry(w, "bigGlobeColors", this.bigGlobeColors, "Use Big Globe's grass, foliage and water colour scripts for LODs.");
			entry(w, "generatorPriority", this.generatorPriority, "Distant Horizons override priority. Higher than Big Globe's built-in generator (10).");
			entry(w, "statsInterval", this.statsInterval, "Log a throughput summary every N sections, 0 = never.");
		}
	}

	private static void entry(Writer w, String key, Object value, String comment) throws IOException {
		w.write("# " + comment + "\n" + key + "=" + value + "\n\n");
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		String value = p.getProperty(key);
		if (value == null) return fallback;
		value = value.trim();
		if (value.equalsIgnoreCase("true")) return true;
		if (value.equalsIgnoreCase("false")) return false;
		return fallback;
	}

	private static int integer(Properties p, String key, int fallback) {
		String value = p.getProperty(key);
		if (value == null) return fallback;
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public String summary() {
		return "maxDetail=" + this.maxDetailLevel
			+ ", underground=" + this.undergroundMode
			+ ", trees=" + this.trees
			+ ", cellCentre=" + this.sampleCellCenter
			+ ", clientGeneration=" + this.clientGeneration
			+ ", vertigoGuard=" + this.vertigoGuard
			+ ", colours=" + this.bigGlobeColors;
	}
}
