package dev.seedgenglobe.dh;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;

/**
 * The threads SeedGenGlobe generates LOD sections on.
 * <p>
 * Distant Horizons runs world generation on a pool of threads it shares with its own file, render loading and update
 * work. Whenever a thread frees up, the pool gives it to the kind of work that has used the least time so far, so
 * generation, which is by far the most expensive, only gets a thread when nothing else is queued; and it stops
 * completely while the camera moves faster than 20 blocks per second. A SeedGenGlobe section takes a few hundred
 * milliseconds, so on a large render distance generation crawls behind Distant Horizons' other work and stands still
 * whenever the player flies. By default sections therefore run on SeedGenGlobe's own threads, slightly below normal
 * priority, so the game and Distant Horizons' own threads come first. Distant Horizons still decides which sections
 * are generated, nearest first, and how many at a time (at most its thread count plus one).
 * <p>
 * {@code generatorThreads = -1} keeps generation on Distant Horizons' pool, with all of its limits.
 */
public final class GeneratorThreads {

	private static final AtomicInteger THREAD_IDS = new AtomicInteger();
	private static final long RECHECK_NANOS = 10_000_000_000L;

	private static @Nullable ThreadPoolExecutor pool;
	private static long checkedAt = Long.MIN_VALUE;
	private static int size;

	private GeneratorThreads() {}

	/**
	 * SeedGenGlobe's own pool, created on first use and resized when Distant Horizons' thread setting changes,
	 * or null when sections are to run on Distant Horizons' threads.
	 */
	public static synchronized @Nullable Executor executor() {
		long now = System.nanoTime();
		if (checkedAt != Long.MIN_VALUE && now - checkedAt < RECHECK_NANOS) return pool != null && size > 0 ? pool : null;
		checkedAt = now;
		int wanted = threadCount();
		if (wanted <= 0) {
			size = 0;
			return null;
		}
		if (pool == null) {
			ThreadFactory factory = runnable -> {
				Thread thread = new Thread(runnable, "SeedGenGlobe Generator " + THREAD_IDS.incrementAndGet());
				thread.setDaemon(true);
				thread.setPriority(Thread.NORM_PRIORITY - 2);
				return thread;
			};
			pool = new ThreadPoolExecutor(wanted, wanted, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
			pool.allowCoreThreadTimeOut(true);
			size = wanted;
			SeedGenGlobe.LOGGER.info("SeedGenGlobe generates LOD sections on {} threads of its own (generatorThreads={}).", wanted, GlobeConfig.get().generatorThreads);
		}
		else if (wanted != size) {
			if (wanted > size) {
				pool.setMaximumPoolSize(wanted);
				pool.setCorePoolSize(wanted);
			}
			else {
				pool.setCorePoolSize(wanted);
				pool.setMaximumPoolSize(wanted);
			}
			SeedGenGlobe.LOGGER.info("SeedGenGlobe now generates LOD sections on {} threads of its own (was {}).", wanted, size);
			size = wanted;
		}
		return pool;
	}

	/** Threads of SeedGenGlobe's own pool that the settings ask for: 0 means Distant Horizons' pool. */
	public static int threadCount() {
		int configured = GlobeConfig.get().generatorThreads;
		if (configured < 0) return 0;
		if (configured > 0) return configured;
		return automatic();
	}

	/**
	 * As many threads as Distant Horizons uses (so every section it lets run at once really runs), one fewer with
	 * C2ME or ScalableLux, whose threads keep chunk loading and lighting near the player going, and always at least
	 * one core left for the game.
	 */
	static int automatic() {
		int processors = Runtime.getRuntime().availableProcessors();
		int threads = dhThreadCount();
		if (threads <= 0) threads = Math.max(1, (processors + 1) / 2);
		if ((ModCompat.C2ME || ModCompat.SCALABLELUX) && threads > 1) threads--;
		return Math.max(1, Math.min(threads, processors - 1));
	}

	/** Distant Horizons' thread setting, or 0 when it cannot be read. */
	public static int dhThreadCount() {
		try {
			Integer value = DhApi.Delayed.configs != null ? DhApi.Delayed.configs.multiThreading().threadCount().getValue() : null;
			return value != null && value > 0 ? value : 0;
		}
		catch (RuntimeException | LinkageError error) {
			return 0;
		}
	}

	/** Whether sections run on SeedGenGlobe's own threads right now. */
	public static synchronized boolean ownThreads() {
		return pool != null && size > 0;
	}

	public static synchronized String describe() {
		if (pool == null || size <= 0) return "Distant Horizons' world generator threads";
		return size + " SeedGenGlobe threads (" + pool.getActiveCount() + " busy, " + pool.getQueue().size() + " waiting)";
	}
}
