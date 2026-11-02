package dev.seedgenglobe.config;

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.gen.CpuLoad;
import dev.seedgenglobe.gen.MemoryLoad;

/**
 * Checks the settings file: every option writes and reads back, unknown or broken values keep their defaults,
 * numbers are clamped, 1.2.0 files are upgraded (generatorThreads=-1), and the comments say when changes apply.
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
		check(text.contains("configVersion=2"), "the file has a version");
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
		System.out.println("ConfigTest: " + checks + " checks passed");
	}

	static Properties load(Path file) throws Exception {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties;
	}
}
