package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.gen.GenProgress;
import dev.seedgenglobe.gen.SectionScheduler;

/**
 * What the progress overlay says, worked out without touching any rendering code: for the dimension the player is in,
 * the Distant Horizons generator's and the Xaero LOD map's sections (running, waiting, speed), how far out each is
 * complete, and what holds generation back right now.
 */
final class OverlayModel {

	static final int TITLE = 0xFF55FF55, TEXT = 0xFFFFFFFF, DIM = 0xFFAAAAAA, WARN = 0xFFFFCC55;

	record Line(String text, int color) {}

	private OverlayModel() {}

	/**
	 * @param dimension the player's dimension
	 * @param dhReason why Distant Horizons asks for few sections right now, or null
	 * @return the lines to show, empty when the overlay should be hidden
	 */
	static List<Line> lines(String dimension, GlobeConfig.OverlayMode mode, List<GenProgress> live, SectionScheduler.Snapshot scheduler, @Nullable String dhReason) {
		List<Line> lines = new ArrayList<>();
		if (mode == GlobeConfig.OverlayMode.OFF) return lines;
		GenProgress lods = null, map = null;
		for (GenProgress progress : live) {
			if (!progress.dimension.equals(dimension)) continue;
			if (progress.map) map = progress;
			else if (lods == null || progress.busy()) lods = progress;
		}
		if (lods == null && map == null) return lines;
		boolean busy = (lods != null && lods.busy()) || (map != null && map.busy());
		if (!busy && mode == GlobeConfig.OverlayMode.WHILE_WORKING) return lines;
		lines.add(new Line("SeedGenGlobe", TITLE));
		if (lods != null) describe(lines, "LODs", lods, "complete out to");
		if (map != null) describe(lines, "Map", map, "no gaps out to");
		if (busy && scheduler.allowed() < scheduler.threads() && !scheduler.limitedBy().isEmpty()) {
			lines.add(new Line("Slower: " + scheduler.limitedBy(), WARN));
		}
		if (busy && dhReason != null && lods != null && lods.busy()) {
			lines.add(new Line(dhReason, WARN));
		}
		return lines;
	}

	private static void describe(List<Line> lines, String what, GenProgress progress, String completeWords) {
		if (progress.busy()) {
			lines.add(new Line(String.format(
				Locale.ROOT, "%s: %d running, %d waiting, %.1f/s",
				what, progress.running(), progress.waiting(), progress.rate()
			), TEXT));
		}
		else {
			lines.add(new Line(what + ": idle", DIM));
		}
		double complete = progress.completeRadius();
		if (complete > 0.0D) {
			int target = progress.targetRadius();
			GlobeConfig.ProgressUnit unit = GlobeConfig.ProgressUnit.current();
			String text = target > 0 && complete < target
				? String.format(Locale.ROOT, "  %s %s of %s %s", completeWords, unit.compact(complete), unit.compact(target), unit.word())
				: String.format(Locale.ROOT, "  %s %s %s", completeWords, unit.compact(target > 0 ? target : complete), unit.word());
			lines.add(new Line(text, DIM));
		}
	}
}
