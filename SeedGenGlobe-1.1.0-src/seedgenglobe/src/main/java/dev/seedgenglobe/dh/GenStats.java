package dev.seedgenglobe.dh;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;

/** Throughput counters for one generator, readable from any thread. */
public final class GenStats {

	public final String name;
	public final LongAdder sections = new LongAdder();
	public final LongAdder columns = new LongAdder();
	public final LongAdder nanos = new LongAdder();
	public final LongAdder errors = new LongAdder();
	public final LongAdder cancelled = new LongAdder();
	public final AtomicLong slowestNanos = new AtomicLong();
	public final AtomicLongArray perDetail = new AtomicLongArray(16);
	public final long createdAt = System.nanoTime();

	public GenStats(String name) {
		this.name = name;
	}

	public void record(int detail, int columnCount, long elapsedNanos, int errorCount) {
		this.sections.increment();
		this.columns.add(columnCount);
		this.nanos.add(elapsedNanos);
		if (errorCount > 0) this.errors.add(errorCount);
		this.slowestNanos.accumulateAndGet(elapsedNanos, Math::max);
		if (detail >= 0 && detail < 16) this.perDetail.incrementAndGet(detail);
		long count = this.sections.sum();
		int interval = GlobeConfig.get().statsInterval;
		if (count == 1 || (interval > 0 && count % interval == 0)) {
			SeedGenGlobe.LOGGER.info("SeedGenGlobe [{}]: {}", this.name, this.summary());
		}
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
			"%d sections, %d columns, %.1f ms/section (slowest %.0f ms), %.0f columns per busy second, %.2f sections/s since start, %d column errors [%s]",
			sections,
			columns,
			sections == 0 ? 0.0D : busyMs / sections,
			this.slowestNanos.get() / 1.0e6D,
			busyMs <= 0.0D ? 0.0D : columns / (busyMs / 1000.0D),
			sections / wallSeconds,
			this.errors.sum(),
			detail
		);
	}
}
