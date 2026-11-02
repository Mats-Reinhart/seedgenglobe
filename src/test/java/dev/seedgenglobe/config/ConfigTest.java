package dev.seedgenglobe.config;

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.OptionSteps;
import dev.seedgenglobe.gen.CpuLoad;
import dev.seedgenglobe.gen.MemoryLoad;

/**
 * Checks the settings file: every option writes and reads back, unknown or broken values keep their defaults,
 * numbers are clamped, 1.2.0 files are upgraded (generatorThreads=-1), and the comments say when changes apply.
 * Also the config screen's values per setting (sliders for more than three, see {@link OptionSteps}) and the
 * chunks/blocks wording of progress distances.
 */
public final class ConfigTest {

	static int checks;

	static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) throws Exception {
		Method save = GlobeConfig.class.getDeclaredMethod("save", Path.class);
		Method read = GlobeConfig.class.getDeclaredMethod("read", Properties.class);
		save.setAccessible(true);
		read.setAccessible(true);
		Path directory = Files.createTempDirectory("seedgenglobe-config");
		Path file = directory.resolve(GlobeConfig.FILE_NAME);

		// defaults round trip
		GlobeConfig defaults = new GlobeConfig();
		save.invoke(defaults, file);
		GlobeConfig loaded = new GlobeConfig();
		read.invoke(loaded, load(file));
		for (GlobeConfig.Option<?> option : GlobeConfig.OPTIONS) {
			check(option.get(loaded).equals(option.get(defaults)), "default of " + option.key + " survives a round trip");
		}
		// comments are wrapped: join their lines before looking for phrases
		String text = Files.readString(file, StandardCharsets.UTF_8).replace("\n# ", " ");
		check(text.contains("configVersion=3"), "the file has a version");
		check(text.contains("Takes effect when a world is loaded again."), "comments say when changes apply");
		check(text.contains("Values: minimal | low | balanced | high | extreme."), "choices are listed");

		// every option changed, round trip
		GlobeConfig changed = new GlobeConfig();
		changed.enabled = false;
		changed.dhGeneration = false;
		changed.dhChunkLodBuilding = false;
		changed.dhRequestBoost = false;
		changed.cpuLoad = CpuLoad.HIGH;
		changed.memoryLoad = MemoryLoad.LOW;
		changed.yieldToServer = false;
		changed.generatorThreads = 5;
		changed.threadPriority = GlobeConfig.ThreadPriority.LOWER;
		changed.fullStructurePrecompute = true;
		changed.progressUnit = GlobeConfig.ProgressUnit.BLOCKS;
		changed.maxDetailLevel = 9;
		changed.undergroundMode = GlobeConfig.UndergroundMode.CARVE;
		changed.sampleCellCenter = false;
		changed.trees = false;
		changed.groundCover = false;
		changed.structures = false;
		changed.structureDetail = 3;
		changed.updateParentSections = true;
		changed.bigGlobeColors = false;
		changed.vertigoGuard = false;
		changed.xaeroLodMap = false;
		changed.xaeroMinimapLodMap = false;
		changed.xaeroLodMapRadius = 512;
		changed.mapFullDetailRadius = 64;
		changed.progressOverlay = GlobeConfig.OverlayMode.ALWAYS;
		changed.overlayPosition = GlobeConfig.OverlayPosition.BOTTOM_LEFT;
		changed.chatProgress = GlobeConfig.ChatMode.ALL;
		changed.statsLogSeconds = 30;
		changed.logSections = true;
		changed.clientGeneration = false;
		changed.clientGenerationWithDhServer = true;
		changed.generatorPriority = 3000;
		save.invoke(changed, file);
		GlobeConfig back = new GlobeConfig();
		read.invoke(back, load(file));
		for (GlobeConfig.Option<?> option : GlobeConfig.OPTIONS) {
			check(option.get(back).equals(option.get(changed)), option.key + " survives a round trip: " + option.get(back) + " vs " + option.get(changed));
			check(!option.get(changed).equals(option.defaultValue()) || option.key.equals("none"), option.key + " was really changed by the test");
		}
		GlobeConfig copy = changed.copy();
		for (GlobeConfig.Option<?> option : GlobeConfig.OPTIONS) check(option.get(copy).equals(option.get(changed)), "copy keeps " + option.key);

		// broken and out of range values
		Properties broken = new Properties();
		broken.setProperty("cpuLoad", "turbo");
		broken.setProperty("trees", "maybe");
		broken.setProperty("statsLogSeconds", "-5");
		broken.setProperty("xaeroLodMapRadius", "999999");
		broken.setProperty("generatorPriority", "1");
		broken.setProperty("mapFullDetailRadius", "abc");
		GlobeConfig fixed = new GlobeConfig();
		read.invoke(fixed, broken);
		check(fixed.cpuLoad == CpuLoad.BALANCED, "unknown preset keeps the default");
		check(fixed.trees, "unknown boolean keeps the default");
		check(fixed.statsLogSeconds == 0, "negative clamped to 0");
		check(fixed.xaeroLodMapRadius == 4096, "radius clamped to 4096");
		check(fixed.generatorPriority == 11, "priority stays above Big Globe's");
		check(fixed.mapFullDetailRadius == 32, "malformed number keeps the default");

		// a 1.2.0 file
		Properties old = new Properties();
		old.setProperty("generatorThreads", "-1");
		old.setProperty("clientThreads", "3");
		old.setProperty("undergroundMode", "decorate");
		old.setProperty("xaeroLodMap", "false");
		GlobeConfig upgraded = new GlobeConfig();
		read.invoke(upgraded, old);
		check(upgraded.generatorThreads == 0, "generatorThreads=-1 becomes automatic");
		check(upgraded.undergroundMode == GlobeConfig.UndergroundMode.DECORATE, "1.2.0 underground mode names still read");
		check(!upgraded.xaeroLodMap, "1.2.0 keys still read");

		// every option has words for the screen
		for (GlobeConfig.Option<?> option : GlobeConfig.OPTIONS) {
			check(!option.label.isEmpty() && option.description.length() > 20, option.key + " has a label and a description");
			check(GlobeConfig.option(option.key) == option, "lookup by key: " + option.key);
		}
		check(GlobeConfig.option("yieldToServer").label.equals("Server tps priority"), "yieldToServer is called Server tps priority");
		check(new GlobeConfig().progressUnit == GlobeConfig.ProgressUnit.CHUNKS, "progress counts chunks by default");
		check(new GlobeConfig().threadPriority == GlobeConfig.ThreadPriority.AUTO && !new GlobeConfig().fullStructurePrecompute, "1.3.1 defaults");

		steps();
		units();
		System.out.println("ConfigTest: " + checks + " checks passed");
	}

	/** Which settings get sliders, the values they offer, and how a slider position maps onto them. */
	static void steps() {
		GlobeConfig config = new GlobeConfig();
		for (GlobeConfig.Option<?> option : GlobeConfig.OPTIONS) {
			List<Object> values = OptionSteps.values(option, option.get(config), 16);
			check(values.size() >= 2, option.key + " offers values");
			check(values.contains(option.get(config)), option.key + " offers its current value " + option.get(config));
			boolean slider = OptionSteps.slider(values);
			boolean expected = option instanceof GlobeConfig.IntOption
				|| (option instanceof GlobeConfig.ChoiceOption<?> choice && choice.values.size() > 3);
			check(slider == expected, option.key + (expected ? " gets a slider" : " gets a button") + " (" + values.size() + " values)");
		}
		for (String key : new String[] { "cpuLoad", "memoryLoad", "overlayPosition", "undergroundMode", "structureDetail", "generatorThreads", "statsLogSeconds" }) {
			check(OptionSteps.slider(OptionSteps.values(GlobeConfig.option(key), GlobeConfig.option(key).get(config), 16)), key + " is a slider");
		}
		for (String key : new String[] { "progressOverlay", "chatProgress", "threadPriority", "progressUnit", "trees", "yieldToServer" }) {
			check(!OptionSteps.slider(OptionSteps.values(GlobeConfig.option(key), GlobeConfig.option(key).get(config), 16)), key + " is a button");
		}
		List<Object> threads = OptionSteps.values(GlobeConfig.option("generatorThreads"), 0, 16);
		check(threads.size() == 17 && threads.get(0).equals(0) && threads.get(16).equals(16), "threads: automatic, then 1 to 16: " + threads);
		check(OptionSteps.values(GlobeConfig.option("generatorThreads"), 40, 16).contains(40), "a thread count from the file stays offered");
		List<Object> radius = OptionSteps.values(GlobeConfig.option("xaeroLodMapRadius"), 300, 16);
		check(radius.contains(300) && radius.indexOf(300) == radius.indexOf(256) + 1, "a hand-edited radius sits in order: " + radius);
		check(OptionSteps.values(GlobeConfig.option("structureDetail"), 5, 16).size() == 16, "structure detail 0 to 15");
		// positions
		for (int count = 2; count <= 20; count++) {
			for (int index = 0; index < count; index++) {
				check(OptionSteps.indexAt(OptionSteps.position(index, count), count) == index, "position round trip " + index + "/" + count);
			}
		}
		check(OptionSteps.indexAt(-1.0D, 5) == 0 && OptionSteps.indexAt(2.0D, 5) == 4 && OptionSteps.indexAt(Double.NaN, 5) == 0, "positions are clamped");
		// a key press nudges the knob by a pixel: still one value
		check(OptionSteps.moved(2, OptionSteps.position(2, 5) + 0.005D, 5, true) == 3, "right arrow moves one value");
		check(OptionSteps.moved(2, OptionSteps.position(2, 5) - 0.005D, 5, true) == 1, "left arrow moves one value");
		check(OptionSteps.moved(4, 1.0D, 5, true) == 4, "right arrow stops at the end");
		check(OptionSteps.moved(2, OptionSteps.position(2, 5) + 0.005D, 5, false) == 2, "a tiny mouse movement keeps the value");
		check(OptionSteps.moved(0, 0.9D, 5, false) == 4, "the mouse sets the value outright");
		check(OptionSteps.next(List.of("a", "b", "c"), "c").equals("a") && OptionSteps.next(List.of("a", "b"), "x").equals("a"), "buttons wrap around");
	}

	/** Progress distances in chunks (default) or blocks. */
	static void units() {
		GlobeConfig.ProgressUnit chunks = GlobeConfig.ProgressUnit.CHUNKS, blocks = GlobeConfig.ProgressUnit.BLOCKS;
		check(chunks.format(16768).equals("1,048 chunks"), "render distance in chunks: " + chunks.format(16768));
		check(blocks.format(16768).equals("16,768 blocks"), "in blocks: " + blocks.format(16768));
		check(chunks.format(2047.9D, 16768).equals("127 of 1,048 chunks"), "partial chunks round down: " + chunks.format(2047.9D, 16768));
		check(blocks.format(2048, 16768).equals("2,048 of 16,768 blocks"), "of, in blocks");
		check(chunks.format(1024).equals("64 chunks"), "the first milestone is 64 chunks");
		check(chunks.compact(16768).equals("1,048") && blocks.compact(16768).equals("16.8k"), "overlay numbers: " + chunks.compact(16768) + " / " + blocks.compact(16768));
		check(chunks.amount(-5) == 0 && chunks.amount(Double.NaN) == 0, "no negative distances");
	}

	static Properties load(Path file) throws Exception {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties;
	}
}
