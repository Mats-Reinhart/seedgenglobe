package dev.seedgenglobe;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

import net.fabricmc.loader.api.FabricLoader;

import dev.seedgenglobe.gen.CpuLoad;
import dev.seedgenglobe.gen.MemoryLoad;

/**
 * Settings for SeedGenGlobe, kept in {@code config/seedgenglobe.properties} and editable in game through Mod Menu.
 * <p>
 * Every setting is described once in {@link #OPTIONS}: its key, default, range, the words the config screen shows and
 * when a change takes effect. The properties file, the config screen and {@link #summary()} are all made from that list.
 * A plain properties file is used on purpose: it needs no extra library and is easy to edit by hand. Unknown or
 * malformed values fall back to their defaults and the file is rewritten with every key present.
 */
public final class GlobeConfig {

	public static final String FILE_NAME = "seedgenglobe.properties";
	/** 2: SeedGenGlobe 1.3.0 (CPU and memory load presets; generatorThreads=-1 is gone). 3: 1.3.1 (new keys only). */
	static final int CONFIG_VERSION = 3;

	private static volatile GlobeConfig current = new GlobeConfig();
	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

	// ------------------------------------------------------------------------------------------------ values

	/** Master switch. When false the mod registers nothing and Distant Horizons behaves as if it were absent. */
	public boolean enabled = true;
	/** Generate Distant Horizons LODs for Big Globe dimensions. Off leaves Distant Horizons to its own generator. */
	public boolean dhGeneration = true;
	/** Let Distant Horizons build LODs from the chunks players load in dimensions SeedGenGlobe generates. */
	public boolean dhChunkLodBuilding = true;
	/** Let Distant Horizons hand SeedGenGlobe as many sections as its threads can take, instead of its own small limits. */
	public boolean dhRequestBoost = true;

	public CpuLoad cpuLoad = CpuLoad.DEFAULT;
	public MemoryLoad memoryLoad = MemoryLoad.DEFAULT;
	/** Run fewer sections at once (and, with {@link ThreadPriority#AUTO}, at lower priority) while the server cannot keep up 20 ticks per second. */
	public boolean yieldToServer = true;
	/** Threads for LOD sections; 0 follows {@link #cpuLoad}. */
	public int generatorThreads = 0;
	/** Priority of the generator threads. */
	public ThreadPriority threadPriority = ThreadPriority.AUTO;

	public int maxDetailLevel = 15;
	public UndergroundMode undergroundMode = UndergroundMode.FILL;
	public boolean sampleCellCenter = true;
	public boolean trees = true;
	public boolean groundCover = true;
	public boolean structures = true;
	public int structureDetail = 5;
	/** Before structure overriders reshape terrain, compute every value any overrider reads (1.3.0), not just the ones the overriders there read. */
	public boolean fullStructurePrecompute = false;
	public boolean updateParentSections = false;
	public boolean bigGlobeColors = true;
	public boolean vertigoGuard = true;

	/** With Xaero's World Map: map the surface of the whole LOD radius. */
	public boolean xaeroLodMap = true;
	/** Show that LOD terrain on Xaero's Minimap as well. */
	public boolean xaeroMinimapLodMap = true;
	/** Radius of the LOD map in chunks; 0 follows Distant Horizons' render distance. */
	public int xaeroLodMapRadius = 0;
	/** Chunks around the player (and next to explored chunks) the LOD map shows one pixel per block. */
	public int mapFullDetailRadius = 32;

	public OverlayMode progressOverlay = OverlayMode.WHILE_WORKING;
	public OverlayPosition overlayPosition = OverlayPosition.TOP_RIGHT;
	public ChatMode chatProgress = ChatMode.MILESTONES;
	public ProgressUnit progressUnit = ProgressUnit.CHUNKS;
	public int statsLogSeconds = 60;
	public boolean logSections = false;

	public boolean clientGeneration = true;
	public boolean clientGenerationWithDhServer = false;

	public int generatorPriority = 2026;

	// ------------------------------------------------------------------------------------------------ choices

	public enum UndergroundMode implements Choice {
		NONE("none", "None"), FILL("fill", "Fill"), CARVE("carve", "Carve"), DECORATE("decorate", "Decorate"), BIGGLOBE("bigglobe", "Big Globe's setting");

		final String id, label;

		UndergroundMode(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override public String id() { return this.id; }
		@Override public String label() { return this.label; }
	}

	public enum OverlayMode implements Choice {
		OFF("off", "Off"), WHILE_WORKING("working", "While working"), ALWAYS("always", "Always");

		final String id, label;

		OverlayMode(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override public String id() { return this.id; }
		@Override public String label() { return this.label; }
	}

	public enum OverlayPosition implements Choice {
		TOP_LEFT("top_left", "Top left"), TOP_RIGHT("top_right", "Top right"), BOTTOM_LEFT("bottom_left", "Bottom left"), BOTTOM_RIGHT("bottom_right", "Bottom right");

		final String id, label;

		OverlayPosition(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override public String id() { return this.id; }
		@Override public String label() { return this.label; }

		public boolean right() {
			return this == TOP_RIGHT || this == BOTTOM_RIGHT;
		}

		public boolean bottom() {
			return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
		}
	}

	public enum ChatMode implements Choice {
		OFF("off", "Off"), MILESTONES("milestones", "Milestones"), ALL("all", "Everything");

		final String id, label;

		ChatMode(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override public String id() { return this.id; }
		@Override public String label() { return this.label; }
	}

	/**
	 * What distances in progress messages are counted in: chunks, like Distant Horizons' render distance, or blocks
	 * (1.3.0). Milestones are the same either way (64 chunks = 1,024 blocks, doubling from there).
	 */
	public enum ProgressUnit implements Choice {
		CHUNKS("chunks", "Chunks"), BLOCKS("blocks", "Blocks");

		final String id, label;

		ProgressUnit(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override public String id() { return this.id; }
		@Override public String label() { return this.label; }

		/** The current setting's unit. */
		public static ProgressUnit current() {
			return GlobeConfig.get().progressUnit;
		}

		/** A distance in blocks, counted in this unit: whole chunks are rounded down, like "complete out to". */
		public long amount(double blocks) {
			if (!(blocks > 0.0D)) return 0L;
			return this == CHUNKS ? (long)(blocks / 16.0D) : (long)(blocks);
		}

		public String word() {
			return this == CHUNKS ? "chunks" : "blocks";
		}

		/** "1,048 chunks" or "16,768 blocks". */
		public String format(double blocks) {
			return String.format(Locale.ROOT, "%,d %s", this.amount(blocks), this.word());
		}

		/** "128 of 1,048 chunks". */
		public String format(double complete, double target) {
			return String.format(Locale.ROOT, "%,d of %,d %s", this.amount(complete), this.amount(target), this.word());
		}

		/** Short form for the overlay: "1,048 chunks", or "12.3k blocks" from 10,000 on. */
		public String compact(double blocks) {
			long amount = this.amount(blocks);
			if (amount >= 10_000L) return String.format(Locale.ROOT, "%.1fk", amount / 1000.0D);
			return String.format(Locale.ROOT, "%,d", amount);
		}
	}

	/**
	 * Priority of SeedGenGlobe's generator threads. In 1.3.0 they ran below normal priority (the CPU load preset's),
	 * and on Windows lost every contest for the CPU with the server and Distant Horizons' own threads, which run at
	 * normal priority: the same sections took several times as long while those were busy.
	 */
	public enum ThreadPriority implements Choice {
		/** Normal priority; the CPU load preset's lower one while the server falls behind (with Server tps priority on). */
		AUTO("auto", "Automatic"),
		/** Always normal priority. */
		NORMAL("normal", "Normal"),
		/** Always the CPU load preset's priority (1.3.0). */
		LOWER("lower", "Lower");

		final String id, label;

		ThreadPriority(String id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override public String id() { return this.id; }
		@Override public String label() { return this.label; }
	}

	/** A value of a choice option: how it is written to the file and shown on screen. */
	public interface Choice {
		String id();
		String label();
	}

	// ------------------------------------------------------------------------------------------------ options

	public enum Category {
		GENERAL("General"),
		PERFORMANCE("Performance"),
		XAERO("Xaero's maps"),
		PROGRESS("Progress"),
		TERRAIN("LOD terrain"),
		MULTIPLAYER("Multiplayer"),
		ADVANCED("Advanced");

		public final String label;

		Category(String label) {
			this.label = label;
		}
	}

	/** When a change takes effect. */
	public enum Apply {
		LIVE(""), REJOIN("Takes effect when a world is loaded again."), RESTART("Takes effect after a game restart.");

		public final String note;

		Apply(String note) {
			this.note = note;
		}
	}

	/** One setting: its key in the file, default, the words the config screen shows, and when a change applies. */
	public abstract static sealed class Option<T> permits BoolOption, IntOption, ChoiceOption {

		public final String key;
		public final Category category;
		public final String label;
		public final String description;
		public final Apply apply;

		Option(String key, Category category, String label, String description, Apply apply) {
			this.key = key;
			this.category = category;
			this.label = label;
			this.description = description;
			this.apply = apply;
		}

		public abstract T get(GlobeConfig config);

		public abstract void set(GlobeConfig config, T value);

		public final T defaultValue() {
			return this.get(new GlobeConfig());
		}

		abstract void read(GlobeConfig config, Properties properties);

		abstract String write(GlobeConfig config);

		/** The value as the config screen shows it. */
		public abstract String display(T value);
	}

	public static final class BoolOption extends Option<Boolean> {

		final Function<GlobeConfig, Boolean> getter;
		final BiConsumer<GlobeConfig, Boolean> setter;

		BoolOption(String key, Category category, String label, String description, Apply apply, Function<GlobeConfig, Boolean> getter, BiConsumer<GlobeConfig, Boolean> setter) {
			super(key, category, label, description, apply);
			this.getter = getter;
			this.setter = setter;
		}

		@Override public Boolean get(GlobeConfig config) { return this.getter.apply(config); }
		@Override public void set(GlobeConfig config, Boolean value) { this.setter.accept(config, value); }

		@Override
		void read(GlobeConfig config, Properties properties) {
			String value = properties.getProperty(this.key);
			if (value == null) return;
			value = value.trim();
			if (value.equalsIgnoreCase("true")) this.set(config, true);
			else if (value.equalsIgnoreCase("false")) this.set(config, false);
		}

		@Override String write(GlobeConfig config) { return String.valueOf(this.get(config)); }
		@Override public String display(Boolean value) { return value ? "On" : "Off"; }
	}

	public static final class IntOption extends Option<Integer> {

		public final int min, max;
		/** What the screen shows for 0, or null when 0 means nothing special. */
		public final String zeroLabel;
		public final String unit;
		final Function<GlobeConfig, Integer> getter;
		final BiConsumer<GlobeConfig, Integer> setter;

		IntOption(String key, Category category, String label, String description, Apply apply, int min, int max, String zeroLabel, String unit, Function<GlobeConfig, Integer> getter, BiConsumer<GlobeConfig, Integer> setter) {
			super(key, category, label, description, apply);
			this.min = min;
			this.max = max;
			this.zeroLabel = zeroLabel;
			this.unit = unit;
			this.getter = getter;
			this.setter = setter;
		}

		@Override public Integer get(GlobeConfig config) { return this.getter.apply(config); }
		@Override public void set(GlobeConfig config, Integer value) { this.setter.accept(config, Math.max(this.min, Math.min(this.max, value))); }

		@Override
		void read(GlobeConfig config, Properties properties) {
			String value = properties.getProperty(this.key);
			if (value == null) return;
			try {
				this.set(config, Integer.parseInt(value.trim()));
			}
			catch (NumberFormatException ignored) {}
		}

		@Override String write(GlobeConfig config) { return String.valueOf(this.get(config)); }

		@Override
		public String display(Integer value) {
			if (value == 0 && this.zeroLabel != null) return this.zeroLabel;
			return this.unit.isEmpty() ? String.valueOf(value) : value + " " + this.unit;
		}
	}

	public static final class ChoiceOption<E extends Enum<E> & Choice> extends Option<E> {

		public final List<E> values;
		final Function<GlobeConfig, E> getter;
		final BiConsumer<GlobeConfig, E> setter;

		ChoiceOption(String key, Category category, String label, String description, Apply apply, E[] values, Function<GlobeConfig, E> getter, BiConsumer<GlobeConfig, E> setter) {
			super(key, category, label, description, apply);
			this.values = List.of(values);
			this.getter = getter;
			this.setter = setter;
		}

		@Override public E get(GlobeConfig config) { return this.getter.apply(config); }
		@Override public void set(GlobeConfig config, E value) { this.setter.accept(config, value); }

		@Override
		void read(GlobeConfig config, Properties properties) {
			String value = properties.getProperty(this.key);
			if (value == null) return;
			for (E choice : this.values) {
				if (choice.id().equalsIgnoreCase(value.trim())) {
					this.set(config, choice);
					return;
				}
			}
		}

		@Override String write(GlobeConfig config) { return this.get(config).id(); }
		@Override public String display(E value) { return value.label(); }

		/** The value after this one, wrapping around (for a cycling button). */
		public E next(E value) {
			int index = this.values.indexOf(value);
			return this.values.get((index + 1) % this.values.size());
		}
	}

	/** CPU and memory presets are enums of their own package; these adapters let them be choice options. */
	public enum CpuChoice implements Choice {
		MINIMAL(CpuLoad.MINIMAL), LOW(CpuLoad.LOW), BALANCED(CpuLoad.BALANCED), HIGH(CpuLoad.HIGH), EXTREME(CpuLoad.EXTREME);

		public final CpuLoad load;

		CpuChoice(CpuLoad load) {
			this.load = load;
		}

		static CpuChoice of(CpuLoad load) {
			return values()[load.ordinal()];
		}

		@Override public String id() { return this.load.id(); }

		@Override
		public String label() {
			return this.load.label() + " (" + this.load.threads() + (this.load.threads() == 1 ? " thread)" : " threads)");
		}
	}

	public enum MemoryChoice implements Choice {
		AUTO(MemoryLoad.AUTO), LOW(MemoryLoad.LOW), MEDIUM(MemoryLoad.MEDIUM), HIGH(MemoryLoad.HIGH);

		public final MemoryLoad load;

		MemoryChoice(MemoryLoad load) {
			this.load = load;
		}

		static MemoryChoice of(MemoryLoad load) {
			return values()[load.ordinal()];
		}

		@Override public String id() { return this.load.id(); }

		@Override
		public String label() {
			return this.load == MemoryLoad.AUTO ? "Automatic (" + this.load.resolve().label() + ")" : this.load.label();
		}
	}

	public static final List<Option<?>> OPTIONS = List.of(
		// general
		new BoolOption("enabled", Category.GENERAL, "SeedGenGlobe",
			"Master switch. Off: SeedGenGlobe does nothing and Distant Horizons and Xaero's maps behave as if it were not installed.",
			Apply.RESTART, c -> c.enabled, (c, v) -> c.enabled = v),
		new BoolOption("dhGeneration", Category.GENERAL, "Distant Horizons LODs",
			"Generate Distant Horizons LODs for Big Globe dimensions from Big Globe's terrain scripts. Off leaves them to Distant Horizons' own (much slower) generator; the Xaero LOD map still works.",
			Apply.REJOIN, c -> c.dhGeneration, (c, v) -> c.dhGeneration = v),
		new BoolOption("dhChunkLodBuilding", Category.GENERAL, "DH LODs from loaded chunks",
			"Let Distant Horizons also build LODs from the real chunks players load, in dimensions SeedGenGlobe generates. While many of those wait, it stops asking SeedGenGlobe for sections. Off frees its threads for SeedGenGlobe, but buildings and other changes players make no longer show in LODs.",
			Apply.LIVE, c -> c.dhChunkLodBuilding, (c, v) -> c.dhChunkLodBuilding = v),
		new BoolOption("xaeroLodMap", Category.GENERAL, "Xaero's World Map LOD map",
			"Map the surface of the whole LOD radius on Xaero's World Map, from Big Globe's terrain scripts. Real chunks replace it as soon as Xaero maps them. Off stops mapping more; LOD tiles already on the map stay until Xaero maps those chunks.",
			Apply.LIVE, c -> c.xaeroLodMap, (c, v) -> c.xaeroLodMap = v),
		new BoolOption("xaeroMinimapLodMap", Category.GENERAL, "Xaero's Minimap LOD map",
			"Show that LOD terrain on Xaero's Minimap too. Off: the minimap leaves out every 64-block square that has only LOD tiles, so it shows the chunks Xaero mapped itself (with LOD tiles that share a square with them). Only matters while the minimap takes its chunks from the World Map.",
			Apply.LIVE, c -> c.xaeroMinimapLodMap, (c, v) -> c.xaeroMinimapLodMap = v),
		// performance
		new ChoiceOption<>("cpuLoad", Category.PERFORMANCE, "CPU load",
			"How many threads compute LOD sections and map sections. Balanced leaves two cores for the game; High and Extreme generate faster but the game will stutter more. Minimal and Low also run at low priority.",
			Apply.LIVE, CpuChoice.values(), c -> CpuChoice.of(c.cpuLoad), (c, v) -> c.cpuLoad = v.load),
		new ChoiceOption<>("memoryLoad", Category.PERFORMANCE, "Memory load",
			"How much memory SeedGenGlobe keeps for speed: queued sections, remembered structures, map regions in memory, and how far Distant Horizons' queues may grow. Automatic picks from the memory Java was given.",
			Apply.LIVE, MemoryChoice.values(), c -> MemoryChoice.of(c.memoryLoad), (c, v) -> c.memoryLoad = v.load),
		new BoolOption("yieldToServer", Category.PERFORMANCE, "Server tps priority",
			"Run fewer sections at once while the server cannot keep up 20 ticks per second, and speed up again once it does.",
			Apply.LIVE, c -> c.yieldToServer, (c, v) -> c.yieldToServer = v),
		new ChoiceOption<>("threadPriority", Category.PERFORMANCE, "Thread priority",
			"Priority of the generator threads. Automatic: normal, like the server's and Distant Horizons' threads, and lower while the server cannot keep up (with Server tps priority on). Normal: always normal. Lower: always below normal, as in 1.3.0, which leaves generation only the CPU time the game does not use. Minimal and Low CPU load always run low.",
			Apply.LIVE, ThreadPriority.values(), c -> c.threadPriority, (c, v) -> c.threadPriority = v),
		new BoolOption("dhRequestBoost", Category.PERFORMANCE, "Feed SeedGenGlobe faster",
			"Let Distant Horizons hand SeedGenGlobe as many sections as SeedGenGlobe's queue holds (its own limit is its thread count plus one), and, while SeedGenGlobe is about to run out of sections, keep asking although its render and file queues are busy, up to the memory load's limit (it stops at 5 tasks per thread). Back to its own limits while memory is short.",
			Apply.LIVE, c -> c.dhRequestBoost, (c, v) -> c.dhRequestBoost = v),
		new IntOption("generatorThreads", Category.PERFORMANCE, "Threads",
			"Threads for LOD sections. Automatic follows the CPU load setting.",
			Apply.LIVE, 0, 64, "Automatic", "", c -> c.generatorThreads, (c, v) -> c.generatorThreads = v),
		// Xaero
		new IntOption("xaeroLodMapRadius", Category.XAERO, "LOD map radius",
			"How far the LOD map reaches, in chunks. Automatic follows Distant Horizons' render distance.",
			Apply.LIVE, 0, 4096, "Automatic", "chunks", c -> c.xaeroLodMapRadius, (c, v) -> c.xaeroLodMapRadius = v),
		new IntOption("mapFullDetailRadius", Category.XAERO, "Full detail radius",
			"Within this many chunks of the player, and right next to chunks Xaero has mapped, the LOD map shows one pixel per block. Farther out it gets coarser, like LODs.",
			Apply.LIVE, 0, 256, "Off", "chunks", c -> c.mapFullDetailRadius, (c, v) -> c.mapFullDetailRadius = v),
		// progress
		new ChoiceOption<>("progressOverlay", Category.PROGRESS, "Progress overlay",
			"A few lines on screen with what SeedGenGlobe is doing: sections running and waiting, speed, and how far out LODs and the map are complete.",
			Apply.LIVE, OverlayMode.values(), c -> c.progressOverlay, (c, v) -> c.progressOverlay = v),
		new ChoiceOption<>("overlayPosition", Category.PROGRESS, "Overlay position",
			"Screen corner of the progress overlay.",
			Apply.LIVE, OverlayPosition.values(), c -> c.overlayPosition, (c, v) -> c.overlayPosition = v),
		new ChoiceOption<>("chatProgress", Category.PROGRESS, "Chat messages",
			"Milestones: when LODs or the map are complete out to 1, 2, 4, 8, ... thousand blocks, and when all is done. Everything: also when generation starts and resumes.",
			Apply.LIVE, ChatMode.values(), c -> c.chatProgress, (c, v) -> c.chatProgress = v),
		new ChoiceOption<>("progressUnit", Category.PROGRESS, "Distances in",
			"Count how far LODs and the map are complete in chunks, like Distant Horizons' render distance, or in blocks. Applies to the overlay, chat messages, the status page and the log.",
			Apply.LIVE, ProgressUnit.values(), c -> c.progressUnit, (c, v) -> c.progressUnit = v),
		new IntOption("statsLogSeconds", Category.PROGRESS, "Log summary interval",
			"While sections are generated, log a summary (started and finished sections, speed, time per phase, Distant Horizons' queues) this often.",
			Apply.LIVE, 0, 3600, "Never", "s", c -> c.statsLogSeconds, (c, v) -> c.statsLogSeconds = v),
		new BoolOption("logSections", Category.PROGRESS, "Log every section",
			"Log a line when each section starts and when it finishes. Many lines; for finding out what generation is doing.",
			Apply.LIVE, c -> c.logSections, (c, v) -> c.logSections = v),
		// terrain
		new BoolOption("trees", Category.TERRAIN, "Trees",
			"Approximate tree canopies so forests remain visible in the distance.",
			Apply.REJOIN, c -> c.trees, (c, v) -> c.trees = v),
		new BoolOption("groundCover", Category.TERRAIN, "Ground cover",
			"Big Globe's grass, flowers, leaf litter and similar ground cover on generated LODs, so they match LODs of explored chunks.",
			Apply.REJOIN, c -> c.groundCover, (c, v) -> c.groundCover = v),
		new BoolOption("structures", Category.TERRAIN, "Structures",
			"Big Globe's structures in generated LODs (obelisks, mega trees, villages, houses, lakes, ...): the terrain they shape and the blocks they place. Server and singleplayer only.",
			Apply.REJOIN, c -> c.structures, (c, v) -> c.structures = v),
		new IntOption("structureDetail", Category.TERRAIN, "Structure detail",
			"Coarsest detail level (0-15) that gets every structure (5 = LOD cells 32 blocks wide). Small structures stop earlier, once a LOD cell is more than half their size (houses and campfires at about 3). Two levels coarser still get lakes and mega trees; obelisks are in every LOD.",
			Apply.REJOIN, 0, 15, null, "", c -> c.structureDetail, (c, v) -> c.structureDetail = v),
		new ChoiceOption<>("undergroundMode", Category.TERRAIN, "Underground",
			"How much of the underground to compute. Fill is fastest and looks the same from above; Big Globe's setting uses Big Globe's own Distant Horizons option.",
			Apply.REJOIN, UndergroundMode.values(), c -> c.undergroundMode, (c, v) -> c.undergroundMode = v),
		new BoolOption("bigGlobeColors", Category.TERRAIN, "Big Globe colours",
			"Tint distant grass, leaves and water with Big Globe's own colour scripts instead of flat per-biome colours.",
			Apply.RESTART, c -> c.bigGlobeColors, (c, v) -> c.bigGlobeColors = v),
		new BoolOption("vertigoGuard", Category.TERRAIN, "Vertigo guard",
			"With Vertigo, keep chunks whose top or bottom sections were not synced from overwriting LODs and Xaero's map.",
			Apply.RESTART, c -> c.vertigoGuard, (c, v) -> c.vertigoGuard = v),
		// multiplayer
		new BoolOption("clientGeneration", Category.MULTIPLAYER, "Generate on this client",
			"Multiplayer: generate LODs on this client from the terrain Big Globe syncs, when the server has no Distant Horizons.",
			Apply.RESTART, c -> c.clientGeneration, (c, v) -> c.clientGeneration = v),
		new BoolOption("clientGenerationWithDhServer", Category.MULTIPLAYER, "Also with a DH server",
			"Multiplayer: generate locally even when the server runs Distant Horizons, instead of downloading LODs from it.",
			Apply.REJOIN, c -> c.clientGenerationWithDhServer, (c, v) -> c.clientGenerationWithDhServer = v),
		// advanced
		new IntOption("maxDetailLevel", Category.ADVANCED, "Coarsest detail level",
			"Coarsest Distant Horizons detail level generated directly (0-15). LOD columns are 2^n blocks wide.",
			Apply.REJOIN, 0, 15, null, "", c -> c.maxDetailLevel, (c, v) -> c.maxDetailLevel = v),
		new BoolOption("sampleCellCenter", Category.ADVANCED, "Sample cell centres",
			"Sample the centre of each LOD cell instead of its corner.",
			Apply.REJOIN, c -> c.sampleCellCenter, (c, v) -> c.sampleCellCenter = v),
		new BoolOption("fullStructurePrecompute", Category.ADVANCED, "Full structure precompute",
			"Before structures reshape the terrain around them, compute every column value any of Big Globe's structure overriders reads, as Big Globe's chunk generator does (1.3.0). Off computes only what the overriders of the structures nearby read, which saves time next to structures; turn on if LODs there differ from the real terrain.",
			Apply.REJOIN, c -> c.fullStructurePrecompute, (c, v) -> c.fullStructurePrecompute = v),
		new BoolOption("updateParentSections", Category.ADVANCED, "Update parent sections",
			"Let generated sections rewrite their coarser parents too (Distant Horizons' default). Off avoids holes while LODs build.",
			Apply.REJOIN, c -> c.updateParentSections, (c, v) -> c.updateParentSections = v),
		new IntOption("generatorPriority", Category.ADVANCED, "DH override priority",
			"Distant Horizons world generator override priority. Must differ from other generator mods; higher than Big Globe's own (10).",
			Apply.REJOIN, 11, 1_000_000, null, "", c -> c.generatorPriority, (c, v) -> c.generatorPriority = v)
	);

	// ------------------------------------------------------------------------------------------------ access

	public static GlobeConfig get() {
		return current;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** Runs whenever the settings change (after {@link #load} or {@link #apply}). */
	public static void onChange(Runnable listener) {
		LISTENERS.add(listener);
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
		try {
			config.save(path);
		}
		catch (IOException exception) {
			Log.LOGGER.warn("SeedGenGlobe could not write {}: {}", path, exception.toString());
		}
		current = config;
		notifyListeners();
		return config;
	}

	/** Makes a changed copy the current settings and saves them (the config screen). */
	public static synchronized void apply(GlobeConfig config) {
		current = config;
		try {
			config.save(path());
		}
		catch (IOException exception) {
			Log.LOGGER.warn("SeedGenGlobe could not write {}: {}", path(), exception.toString());
		}
		notifyListeners();
	}

	private static void notifyListeners() {
		for (Runnable listener : LISTENERS) {
			try {
				listener.run();
			}
			catch (RuntimeException exception) {
				Log.LOGGER.warn("SeedGenGlobe: applying changed settings failed", exception);
			}
		}
	}

	/** Makes these the current settings without saving them or telling anyone (offline tests). */
	public static void useForTests(GlobeConfig config) {
		current = config;
	}

	/** A copy to edit (the config screen edits a copy and applies it on save). */
	public GlobeConfig copy() {
		GlobeConfig copy = new GlobeConfig();
		for (Option<?> option : OPTIONS) copyValue(option, this, copy);
		return copy;
	}

	private static <T> void copyValue(Option<T> option, GlobeConfig from, GlobeConfig to) {
		option.set(to, option.get(from));
	}

	void read(Properties properties) {
		int version = 1;
		try {
			version = Integer.parseInt(properties.getProperty("configVersion", "1").trim());
		}
		catch (NumberFormatException ignored) {}
		for (Option<?> option : OPTIONS) option.read(this, properties);
		if (version < 2) {
			// 1.2.0 and older: -1 meant Distant Horizons' own threads, which 1.3.0 no longer uses
			String threads = properties.getProperty("generatorThreads");
			if (threads != null && threads.trim().equals("-1")) this.generatorThreads = 0;
			// clientThreads (client generation's own budget) is now the shared CPU load setting
		}
	}

	void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(FILE_NAME + ".tmp");
		try (Writer w = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			w.write("# SeedGenGlobe: fast Distant Horizons LODs and Xaero LOD maps for Big Globe worlds.\n");
			w.write("# Edit here or in game through Mod Menu. Each entry says when a change takes effect.\n\n");
			w.write("configVersion=" + CONFIG_VERSION + "\n\n");
			Category category = null;
			for (Option<?> option : OPTIONS) {
				if (option.category != category) {
					category = option.category;
					w.write("# ---- " + category.label + " ----\n\n");
				}
				StringBuilder comment = new StringBuilder(option.label).append(": ").append(option.description);
				if (option instanceof ChoiceOption<?> choice) {
					comment.append(" Values: ");
					for (int i = 0; i < choice.values.size(); i++) {
						if (i > 0) comment.append(" | ");
						comment.append(choice.values.get(i).id());
					}
					comment.append('.');
				}
				else if (option instanceof IntOption range) {
					comment.append(" Range ").append(range.min).append(" to ").append(range.max);
					if (range.zeroLabel != null && range.min <= 0) comment.append(", 0 = ").append(range.zeroLabel.toLowerCase(Locale.ROOT));
					comment.append('.');
				}
				if (!option.apply.note.isEmpty()) comment.append(' ').append(option.apply.note);
				for (String line : wrap(comment.toString(), 116)) w.write("# " + line + "\n");
				w.write(option.key + "=" + option.write(this) + "\n\n");
			}
		}
		try {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicMoveUnsupported) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static List<String> wrap(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && line.length() + 1 + word.length() > width) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	public static Option<?> option(String key) {
		for (Option<?> option : OPTIONS) {
			if (option.key.equals(key)) return option;
		}
		throw new IllegalArgumentException(key);
	}

	/** Short description of the settings that shape generation, for the log and the status screen. */
	public String summary() {
		return "maxDetail=" + this.maxDetailLevel
			+ ", underground=" + this.undergroundMode.id()
			+ ", trees=" + this.trees
			+ ", groundCover=" + this.groundCover
			+ ", structures=" + (this.structures ? "up to detail " + this.structureDetail : "off")
			+ ", parentUpdates=" + this.updateParentSections
			+ ", cellCentre=" + this.sampleCellCenter
			+ (this.structures && this.fullStructurePrecompute ? " (full precompute)" : "")
			+ ", cpu=" + this.cpuLoad.id() + (this.generatorThreads > 0 ? " (" + this.generatorThreads + " threads)" : "")
			+ ", priority=" + this.threadPriority.id()
			+ ", memory=" + this.memoryLoad.id() + (this.memoryLoad == MemoryLoad.AUTO ? " (" + this.memoryLoad.resolve().id() + ")" : "")
			+ ", dhChunkLods=" + this.dhChunkLodBuilding
			+ ", clientGeneration=" + this.clientGeneration
			+ ", vertigoGuard=" + this.vertigoGuard
			+ ", colours=" + this.bigGlobeColors
			+ ", xaeroLodMap=" + this.xaeroLodMap + (this.xaeroLodMap ? " (radius " + (this.xaeroLodMapRadius == 0 ? "DH" : this.xaeroLodMapRadius) + ", minimap " + this.xaeroMinimapLodMap + ")" : "");
	}
}
