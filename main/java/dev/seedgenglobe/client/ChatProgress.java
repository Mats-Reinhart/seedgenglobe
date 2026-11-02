package dev.seedgenglobe.client;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.gen.GenProgress;

/**
 * Turns generation progress into chat messages for the player (singleplayer, and multiplayer client generation):
 * milestones (LODs or the map complete out to 1, 2, 4, 8, ... thousand blocks), and "done" when a run of generation
 * that took a while ends; with {@code chatProgress=all} also when generation starts and resumes. Only about the
 * dimension the player is in, and never more than one message every few seconds per producer.
 */
final class ChatProgress {

	/** A "done" message only follows generation that ran at least this long, so short top-ups stay quiet. */
	static final long DONE_AFTER_NANOS = 60_000_000_000L;
	static final long COOLDOWN_NANOS = 5_000_000_000L;

	private static final Map<GenProgress, long[]> STATE = new ConcurrentHashMap<>();

	private ChatProgress() {}

	static void register() {
		GenProgress.LISTENERS.add(ChatProgress::onEvent);
	}

	private static void onEvent(GenProgress.Event event) {
		GlobeConfig.ChatMode mode = GlobeConfig.get().chatProgress;
		if (mode == GlobeConfig.ChatMode.OFF) return;
		GenProgress progress = event.progress();
		long now = System.nanoTime();
		// [0] = when the current run started, [1] = last message
		long[] state = STATE.computeIfAbsent(progress, key -> new long[] { now, 0L });
		String text;
		switch (event.type()) {
			case STARTED, RESUMED -> {
				state[0] = now;
				if (mode != GlobeConfig.ChatMode.ALL) return;
				text = progress.map ? "computing the Xaero LOD map" : (event.type() == GenProgress.EventType.STARTED ? "generating LODs" : "generating LODs again");
			}
			case MILESTONE -> text = String.format(Locale.ROOT, progress.map ? "Xaero LOD map has no gaps out to %,d blocks" : "LODs complete out to %,d blocks", (long)(event.radius()));
			case DONE -> {
				if (now - state[0] < DONE_AFTER_NANOS && mode != GlobeConfig.ChatMode.ALL) return;
				int target = progress.targetRadius();
				String radius = target > 0 ? String.format(Locale.ROOT, " out to %,d blocks", target) : "";
				text = progress.map ? "Xaero LOD map is complete" + radius : "LODs are complete" + radius + String.format(Locale.ROOT, " (%,d sections)", progress.finished());
			}
			default -> {
				return;
			}
		}
		if (now - state[1] < COOLDOWN_NANOS && event.type() != GenProgress.EventType.DONE) return;
		state[1] = now;
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> show(minecraft, progress, text));
	}

	private static void show(Minecraft minecraft, GenProgress progress, String text) {
		if (minecraft.level == null || minecraft.player == null) return;
		if (!minecraft.level.dimension().identifier().toString().equals(progress.dimension)) return;
		Component message = Component.literal("[SeedGenGlobe] ").withStyle(ChatFormatting.DARK_GREEN)
			.append(Component.literal(text).withStyle(ChatFormatting.GRAY));
		minecraft.player.sendSystemMessage(message);
	}
}
