package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.GlobeMixinPlugin;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.gen.MemoryWatch;
import dev.seedgenglobe.gen.SectionScheduler;
import dev.seedgenglobe.gen.ServerLoad;

/**
 * Everything SeedGenGlobe can say about what it is doing on this client (and its singleplayer server), for the
 * status screen. Replaces the {@code /seedgenglobeclient} command of 1.2.0.
 */
public final class StatusReport {

	private StatusReport() {}

	public static List<String> lines(Minecraft minecraft) {
		GlobeConfig config = GlobeConfig.get();
		List<String> lines = new ArrayList<>();
		lines.add("SeedGenGlobe " + ModCompat.version(SeedGenGlobe.MOD_ID) + (config.enabled ? "" : " (switched off)"));
		lines.add("Settings: " + config.summary());
		lines.add("Threads: " + SectionScheduler.get().describe());
		double tick = ServerLoad.averageMillis();
		lines.add(String.format(
			java.util.Locale.ROOT,
			"Heap: %.0f%% live after the last collection (%s)%s",
			MemoryWatch.liveRatio() * 100.0D,
			MemoryWatch.pressure().name().toLowerCase(java.util.Locale.ROOT),
			tick > 0.0D ? String.format(java.util.Locale.ROOT, "; server ticks take %.0f ms", tick) : ""
		));
		if (ModCompat.DISTANT_HORIZONS) {
			try {
				lines.addAll(dev.seedgenglobe.dh.DiagnosticsBridge.statusLines());
			}
			catch (LinkageError error) {
				lines.add("Distant Horizons API mismatch: " + error);
			}
		}
		else {
			lines.add("Distant Horizons is not installed, so there are no LODs to generate.");
		}
		if (minecraft.hasSingleplayerServer() && ModCompat.DISTANT_HORIZONS && !config.dhGeneration) {
			lines.add("Distant Horizons LODs are switched off in the settings.");
		}
		String colours;
		if (!config.bigGlobeColors) colours = "off in the settings";
		else if (GlobeMixinPlugin.SKIPPED.containsKey("ClientBlockStateColorCacheMixin")) colours = "unavailable with this Distant Horizons build";
		else if (!GlobeColors.enabled()) colours = "off after an error (see the log)";
		else colours = "on";
		lines.add("Big Globe colours for LODs: " + colours);
		lines.add("Distant Horizons LODs from loaded chunks: " + (config.dhChunkLodBuilding ? "on" : "off in dimensions SeedGenGlobe generates"));
		lines.addAll(SeedGenGlobeClient.xaeroStatus());
		lines.addAll(GlobeMixinPlugin.problems());
		for (String line : ModCompat.describe()) lines.add("- " + line);
		return lines;
	}
}
