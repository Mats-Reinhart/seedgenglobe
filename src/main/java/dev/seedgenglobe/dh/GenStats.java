package dev.seedgenglobe.dh;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

import org.jetbrains.annotations.Nullable;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;

/**
 * Throughput counters for one generator, readable from any thread.
 * <p>
 * A summary goes to the log every {@code statsLogSeconds} while sections are being generated, with the rate since the
 * last summary and how many sections ran at once, and once when Distant Horizons stops asking for sections, with
 * what its own queues were doing at that moment. Generation is driven entirely by Distant Horizons' requests, so a
 * quiet log means it did not ask, and the stall line says why it might not have.
 */
public final class GenStats {

	private static final Set<GenStats> LIVE = new CopyOnWriteArraySet<>();
	private static @Nullable ScheduledExecutorService reporter;

	public final String name;
	public final LongAdder sections = new LongAdder();
	public final LongAdder columns = new LongAdder();
	public final LongAdder nanos = new LongAdder();
	public final LongAdder errors = new LongAdder();
	public final LongAdder cancelled = new LongAdder();
	public final AtomicLong slowestNanos = new AtomicLong();
	public final AtomicLongArray perDetail = new AtomicLongArray(16);
	/** Sections handed to SeedGenGlobe and not started yet, and sections being generated. */
	public final AtomicInteger waiting = new AtomicInteger(), running = new AtomicInteger();
	public final long createdAt = System.nanoTime();
	/** When the last section was finished; starts at creation, so a new level counts as busy for a moment. */
	private volatile long lastSectionAt = this.createdAt;

	private final java.util.concurrent.atomic.AtomicBoolean firstLogged = new java.util.concurrent.atomic.AtomicBoolean();
	private final Object logLock = new Object();
	private long loggedAt = this.createdAt, loggedSections, loggedBusyNanos;
	private boolean stallLogged;

	public GenStats(String name) {
		this.name = name;
		LIVE.add(this);
		startReporter();
	}

	/** Stops the periodic summaries for this generator. */
	public void close() {
		LIVE.remove(this);
	}

	public long lastSectionAt() {
		return this.lastSectionAt;
	}

	public void record(int detail, int columnCount, long elapsedNanos, int errorCount) {
		this.sections.increment();
		this.columns.add(columnCount);
		this.nanos.add(elapsedNanos);
		if (errorCount > 0) this.errors.add(errorCount);
		this.slowestNanos.accumulateAndGet(elapsedNanos, Math::max);
		if (detail >= 0 && detail < 16) this.perDetail.incrementAndGet(detail);
		long now = System.nanoTime();
		this.lastSectionAt = now;
		if (this.firstLogged.compareAndSet(false, true)) {
			SeedGenGlobe.LOGGER.info(
				"SeedGenGlobe [{}]: first section generated after {} s, in {} ms, on {}.",
				this.name,
				String.format(Locale.ROOT, "%.1f", (now - this.createdAt) / 1.0e9D),
				String.format(Locale.ROOT, "%.0f", elapsedNanos / 1.0e6D),
				GeneratorThreads.describe()
			);
		}
		this.report(now);
	}

	/** Logs a summary if one is due. Called after every section and every few seconds by the reporter thread. */
	void report(long now) {
		int seconds = GlobeConfig.get().statsLogSeconds;
		if (seconds <= 0) return;
		long interval = seconds * 1_000_000_000L;
		String line;
		synchronized (this.logLock) {
			if (now - this.loggedAt < interval) return;
			long sections = this.sections.sum();
			long busy = this.nanos.sum();
			long newSections = sections - this.loggedSections;
			double wall = (now - this.loggedAt) / 1.0e9D;
			if (newSections > 0) {
				line = String.format(
					Locale.ROOT,
					"%d sections in the last %.0f s (%.1f per second, %.1f at a time), %d in total, %.0f ms per section; %s",
					newSections,
					wall,
					newSections / wall,
					(busy - this.loggedBusyNanos) / 1.0e9D / wall,
					sections,
					(busy - this.loggedBusyNanos) / 1.0e6D / newSections,
					GeneratorThreads.describe()
				);
				this.stallLogged = false;
			}
			else {
				double idle = (now - this.lastSectionAt) / 1.0e9D;
				if (this.stallLogged || sections == 0 || idle < seconds) {
					this.loggedAt = now;
					return;
				}
				this.stallLogged = true;
				line = String.format(
					Locale.ROOT,
					"Distant Horizons has asked for no section in the last %.0f s (%d generated so far): everything within its render distance may be done, or it is busy with its own work",
					idle,
					sections
				);
			}
			this.loggedAt = now;
			this.loggedSections = sections;
			this.loggedBusyNanos = busy;
		}
		String pipeline = DhPipeline.describe(GeneratorThreads.ownThreads());
		SeedGenGlobe.LOGGER.info("SeedGenGlobe [{}]: {}{}", this.name, line, pipeline != null ? ". " + pipeline : "");
	}

	private static synchronized void startReporter() {
		if (reporter != null) return;
		reporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SeedGenGlobe Stats");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		reporter.scheduleWithFixedDelay(() -> {
			long now = System.nanoTime();
			for (GenStats stats : LIVE) {
				try {
					stats.report(now);
				}
				catch (RuntimeException | LinkageError error) {
					SeedGenGlobe.LOGGER.debug("SeedGenGlobe: statistics report failed", error);
				}
			}
		}, 5L, 5L, TimeUnit.SECONDS);
	}

	public String summary() {
		long sections = this.sections.sum();
		long columns = this.columns.sum();
		double busyMs = this.nanos.sum() / 1.0e6D;
		double wallSeconds = Math.max((System.nanoTime() - this.createdAt) / 1.0e9D, 1.0e-3D);
		StringBuilder detail = new StringBuilder();
		for (int level = 0; level < 16; level++) {
			long n = this.perDetail.get(level);
			if (n != 0) {
				if (detail.length() > 0) detail.append(' ');
				detail.append('d').append(level).append('=').append(n);
			}
		}
		return String.format(
			Locale.ROOT,
			"%d sections, %d columns, %.1f ms/section (slowest %.0f ms), %.0f columns per busy second, %.2f sections/s since start, %d running / %d waiting now, last one %.0f s ago, %d column errors [%s]",
			sections,
			columns,
			sections == 0 ? 0.0D : busyMs / sections,
			this.slowestNanos.get() / 1.0e6D,
			busyMs <= 0.0D ? 0.0D : columns / (busyMs / 1000.0D),
			sections / wallSeconds,
			this.running.get(),
			this.waiting.get(),
			(System.nanoTime() - this.lastSectionAt) / 1.0e9D,
			this.errors.sum(),
			detail
		);
	}
}
