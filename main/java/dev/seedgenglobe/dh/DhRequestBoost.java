package dev.seedgenglobe.dh;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.Log;
import dev.seedgenglobe.gen.MemoryWatch;
import dev.seedgenglobe.gen.SectionScheduler;

/**
 * How far Distant Horizons may go beyond its own request limits in dimensions SeedGenGlobe generates
 * ({@code dhRequestBoost}).
 * <p>
 * Distant Horizons 3.0.3 keeps at most its thread count plus one sections in flight with a world generator
 * ({@code WorldGenerationQueue.isGeneratorBusy}), and asks for new sections only while its render loader and its file
 * queue each hold fewer than five tasks per thread ({@code LodQuadTree.threadPoolCanAcceptWorldGenTasks}). Both limits
 * suit its own generator, which runs on its threads; SeedGenGlobe runs sections on threads of its own, sized by the CPU
 * load setting, so on machines where Distant Horizons uses few threads it was asked for very few sections at a time.
 * With the boost, Distant Horizons may have as many sections in flight as SeedGenGlobe's queue holds, and keeps asking
 * until its queues hold the memory load's number of tasks per thread. Both fall back to Distant Horizons' own limits
 * while the heap is nearly full. The mixins that call this are {@code WorldGenerationQueueMixin} and
 * {@code LodQuadTreeMixin}; every read here is guarded, since a hook must never break Distant Horizons' own threads.
 */
public final class DhRequestBoost {

	/** Distant Horizons' own limit for its render loader and file queue, per thread. */
	public static final int DH_QUEUE_PER_THREAD = 5;

	private static volatile boolean broken;

	private DhRequestBoost() {}

	/** Whether the boost applies right now. */
	public static boolean active() {
		GlobeConfig config = GlobeConfig.get();
		return config.enabled && config.dhRequestBoost && !broken && MemoryWatch.pressure() == MemoryWatch.Pressure.NORMAL;
	}

	/** Distant Horizons' thread count (its CPU setting). */
	static int dhThreads() {
		return Math.max(1, Config.Common.MultiThreading.numberOfThreads.get());
	}

	/**
	 * How many sections Distant Horizons may have in flight with SeedGenGlobe's generator, or -1 to keep its own limit
	 * (its thread count plus one).
	 */
	public static int inFlightLimit() {
		if (!active()) return -1;
		try {
			return Math.max(dhThreads() + 1, SectionScheduler.get().capacity());
		}
		catch (RuntimeException | LinkageError error) {
			fail(error);
			return -1;
		}
	}

	/** Tasks per Distant Horizons thread its queues may hold while it still asks for sections. */
	public static int queuePerThread() {
		return active() ? Math.max(DH_QUEUE_PER_THREAD, GlobeConfig.get().memoryLoad.dhQueuePerThread()) : DH_QUEUE_PER_THREAD;
	}

	/**
	 * Whether Distant Horizons' render loader and file queue are below the boosted limit. Asked only after its own,
	 * stricter check said no.
	 */
	public static boolean queuesHaveRoom() {
		if (!active()) return false;
		try {
			int limit = dhThreads() * queuePerThread();
			PriorityTaskPicker.Executor render = ThreadPoolUtil.getRenderLoadingExecutor();
			if (render != null && render.getQueueSize() >= limit) return false;
			PriorityTaskPicker.Executor files = ThreadPoolUtil.getFileHandlerExecutor();
			return files == null || files.getQueueSize() < limit;
		}
		catch (RuntimeException | LinkageError error) {
			fail(error);
			return false;
		}
	}

	private static void fail(Throwable error) {
		if (broken) return;
		broken = true;
		Log.LOGGER.warn("SeedGenGlobe: could not read Distant Horizons' queues; dhRequestBoost is off until the game restarts.", error);
	}
}
