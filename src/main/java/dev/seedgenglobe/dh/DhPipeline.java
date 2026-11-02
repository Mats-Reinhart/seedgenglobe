package dev.seedgenglobe.dh;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.GlobeMixinPlugin;

/**
 * Reads the state of Distant Horizons' own work queues, which decide how often it asks SeedGenGlobe for sections.
 * <p>
 * Distant Horizons only requests new sections while its render loader and its file queue each hold fewer than five
 * tasks per thread (more with {@code dhRequestBoost} while SeedGenGlobe runs short of sections, see
 * {@link DhRequestBoost}) and while fewer than twenty chunk LOD
 * updates per thread wait, and (on its own world generator threads) only runs them while the camera moves slower than
 * 20 blocks per second. None of this is part of its API, so every read is guarded and simply reports nothing on a
 * Distant Horizons build that works differently.
 */
public final class DhPipeline {

	private static volatile boolean broken, chunkUpdatesBroken;

	private DhPipeline() {}

	/** Snapshot of the queues, or null when they cannot be read. {@code chunkUpdates} is -1 when it cannot be read. */
	public record State(int threads, int renderQueue, int fileQueue, int worldGenQueue, int worldGenRunning, boolean worldGenPaused, int queueLimit, int chunkUpdates) {

		/** The limit for both queues before Distant Horizons stops requesting sections (5 per thread, or boosted). */
		public int requestLimit() {
			return this.queueLimit;
		}

		public boolean renderBacklog() {
			return this.renderQueue >= this.requestLimit();
		}

		public boolean fileBacklog() {
			return this.fileQueue >= this.requestLimit();
		}

		/** Distant Horizons requests no sections while this many chunk LOD updates wait. */
		public int chunkUpdateLimit() {
			return 20 * this.threads;
		}

		public boolean chunkUpdateBacklog() {
			return this.chunkUpdates >= this.chunkUpdateLimit();
		}
	}

	public static @Nullable State read() {
		if (broken) return null;
		try {
			int threads = Math.max(1, Config.Common.MultiThreading.numberOfThreads.get());
			PriorityTaskPicker.Executor render = ThreadPoolUtil.getRenderLoadingExecutor();
			PriorityTaskPicker.Executor files = ThreadPoolUtil.getFileHandlerExecutor();
			PriorityTaskPicker.Executor worldGen = ThreadPoolUtil.getWorldGenExecutor();
			boolean boosted = GlobeMixinPlugin.APPLIED.contains("LodQuadTreeMixin") && DhRequestBoost.queuesExtended();
			return new State(
				threads,
				render != null ? render.getQueueSize() : 0,
				files != null ? files.getQueueSize() : 0,
				worldGen != null ? worldGen.getQueueSize() : 0,
				worldGen != null ? worldGen.getRunningTaskCount() : 0,
				!ThreadPoolUtil.worldGenThreadsCanRun(),
				threads * (boosted ? DhRequestBoost.queuePerThread() : DhRequestBoost.DH_QUEUE_PER_THREAD),
				chunkUpdates()
			);
		}
		catch (LinkageError error) {
			broken = true;
			return null;
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	private static int chunkUpdates() {
		if (chunkUpdatesBroken) return -1;
		try {
			return WorldChunkUpdateManager.INSTANCE.getTotalQueuedCount();
		}
		catch (RuntimeException | LinkageError error) {
			chunkUpdatesBroken = true;
			return -1;
		}
	}

	/** Why Distant Horizons may be asking for few sections right now; empty when nothing holds it back. */
	public static List<String> reasons(@Nullable State state, boolean ownThreads) {
		List<String> reasons = new ArrayList<>();
		if (state == null) return reasons;
		if (state.chunkUpdateBacklog()) {
			reasons.add(String.format(
				Locale.ROOT,
				"%d LOD updates from loaded chunks wait (it asks for new sections below %d)%s",
				state.chunkUpdates(), state.chunkUpdateLimit(),
				GlobeConfig.get().dhChunkLodBuilding ? "; dhChunkLodBuilding=false leaves its threads to SeedGenGlobe" : ""
			));
		}
		if (state.renderBacklog()) {
			reasons.add(String.format(Locale.ROOT, "its render loader has %d sections queued (it asks for new ones below %d)", state.renderQueue(), state.requestLimit()));
		}
		if (state.fileBacklog()) {
			reasons.add(String.format(Locale.ROOT, "its file queue has %d tasks (it asks for new sections below %d)", state.fileQueue(), state.requestLimit()));
		}
		if (!ownThreads && state.worldGenPaused()) {
			reasons.add("its world generation is paused while the camera moves faster than 20 blocks per second");
		}
		if (!ownThreads && state.worldGenQueue() > 0 && !state.worldGenPaused()) {
			reasons.add(String.format(Locale.ROOT, "%d sections wait for one of its %d threads, which go to its file and render work first", state.worldGenQueue(), state.threads()));
		}
		return reasons;
	}

	/**
	 * A few words for the overlay when Distant Horizons holds back its requests and SeedGenGlobe's threads run short of
	 * sections because of it, or null. (With enough sections queued, Distant Horizons waiting for its own queues to
	 * drain slows nothing down: since 1.3.1 that is how generation and render loading take turns.)
	 */
	public static @Nullable String shortReason() {
		State state = read();
		if (state == null) return null;
		try {
			if (!dev.seedgenglobe.gen.SectionScheduler.get().shortOfWork()) return null;
		}
		catch (RuntimeException exception) {
			return null;
		}
		if (state.chunkUpdateBacklog()) return "Waiting for Distant Horizons' chunk updates (" + state.chunkUpdates() + ")";
		if (state.renderBacklog()) return "Waiting for Distant Horizons' render queue (" + state.renderQueue() + ")";
		if (state.fileBacklog()) return "Waiting for Distant Horizons' file queue (" + state.fileQueue() + ")";
		return null;
	}

	/** One line for logs and the status command, or null when the queues cannot be read. */
	public static @Nullable String describe(boolean ownThreads) {
		State state = read();
		if (state == null) return null;
		StringBuilder line = new StringBuilder(String.format(
			Locale.ROOT,
			"Distant Horizons: %d threads, render loader queue %d, file queue %d (it asks for sections below %d each)",
			state.threads(), state.renderQueue(), state.fileQueue(), state.requestLimit()
		));
		if (state.chunkUpdates() > 0) line.append(String.format(Locale.ROOT, ", %d chunk LOD updates waiting", state.chunkUpdates()));
		if (!ownThreads) {
			line.append(String.format(Locale.ROOT, ", world generator %d running / %d waiting%s", state.worldGenRunning(), state.worldGenQueue(), state.worldGenPaused() ? " (paused: camera moving fast)" : ""));
		}
		List<String> reasons = reasons(state, ownThreads);
		if (!reasons.isEmpty()) line.append("; holding back: ").append(String.join("; ", reasons));
		return line.toString();
	}
}
