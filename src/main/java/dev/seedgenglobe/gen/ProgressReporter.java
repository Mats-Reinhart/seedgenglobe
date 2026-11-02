package dev.seedgenglobe.gen;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

import dev.seedgenglobe.Log;

/**
 * One low-priority thread that, once a second, lets every {@link GenProgress} log and fire its events and lets the
 * {@link SectionScheduler} adjust to the server's and the heap's load.
 */
final class ProgressReporter {

	private static @Nullable ScheduledExecutorService executor;

	private ProgressReporter() {}

	static synchronized void start() {
		if (executor != null) return;
		executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SeedGenGlobe Stats");
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			return thread;
		});
		executor.scheduleWithFixedDelay(ProgressReporter::tick, 1L, 1L, TimeUnit.SECONDS);
	}

	private static void tick() {
		try {
			SectionScheduler.get().maintain();
		}
		catch (RuntimeException | LinkageError error) {
			Log.LOGGER.debug("SeedGenGlobe: scheduler maintenance failed", error);
		}
		long now = System.nanoTime();
		for (GenProgress progress : GenProgress.LIVE) {
			try {
				progress.report(now);
			}
			catch (RuntimeException | LinkageError error) {
				Log.LOGGER.debug("SeedGenGlobe: progress report failed", error);
			}
		}
	}
}
