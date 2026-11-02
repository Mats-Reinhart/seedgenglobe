package dev.seedgenglobe.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.seedgenglobe.GlobeConfig;

/**
 * Checks the section scheduler without Minecraft: nearest-first order (and that it follows the player), weights by
 * kind, aging, dropping cancelled jobs, cancelling a group, part-way cancellation, waiting for a group to stop, the
 * CPU presets' thread counts and the progress counters.
 */
public final class SchedulerTest {

	static int checks;

	static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}

	/** A job that records when it ran. */
	static final class Probe extends SectionScheduler.Job {

		final String name;
		final List<String> log;
		final CountDownLatch gate;
		final AtomicBoolean cancelled = new AtomicBoolean();
		final GenProgress progress;
		volatile boolean dropped;

		Probe(String name, SectionScheduler.Kind kind, Object group, int x, int z, List<String> log, CountDownLatch gate, GenProgress progress) {
			super(kind, group, "test:world", x, z, 0);
			this.name = name;
			this.log = log;
			this.gate = gate;
			this.progress = progress;
		}

		@Override
		public boolean cancelled() {
			return this.cancelled.get();
		}

		@Override
		protected void execute() throws Exception {
			this.gate.await(10, TimeUnit.SECONDS);
			synchronized (this.log) {
				this.log.add(this.name);
			}
		}

		@Override
		protected void dropped() {
			this.dropped = true;
		}

		@Override
		protected GenProgress progress() {
			return this.progress;
		}
	}

	public static void main(String[] args) throws Exception {
		presets();
		priorities();
		scoring();
		GlobeConfig config = new GlobeConfig();
		config.generatorThreads = 1;
		config.yieldToServer = false;
		GlobeConfig.useForTests(config);
		SectionScheduler scheduler = SectionScheduler.get();
		scheduler.configure();
		check(scheduler.threads() == 1, "one thread configured");
		ordering(scheduler);
		cancelling(scheduler);
		stopping(scheduler);
		System.out.println("SchedulerTest: " + checks + " checks passed");
	}

	static void presets() {
		check(CpuLoad.MINIMAL.threads(16) == 1, "minimal is one thread");
		check(CpuLoad.BALANCED.threads(16) == 6, "balanced on 8 cores / 16 threads: " + CpuLoad.BALANCED.threads(16));
		check(CpuLoad.LOW.threads(16) == 2, "low on 16: " + CpuLoad.LOW.threads(16));
		check(CpuLoad.HIGH.threads(16) == 12, "high on 16: " + CpuLoad.HIGH.threads(16));
		check(CpuLoad.EXTREME.threads(16) == 15, "extreme on 16");
		for (int logical = 1; logical <= 64; logical++) {
			int previous = 0;
			for (CpuLoad load : CpuLoad.values()) {
				int threads = load.threads(logical);
				check(threads >= 1 && threads <= Math.max(1, logical), load + " on " + logical + " gives " + threads);
				if (load != CpuLoad.MINIMAL) check(threads >= previous, "presets grow with the load on " + logical + ": " + load);
				previous = threads;
			}
		}
		check(CpuLoad.BALANCED.threads(4) == 2 && CpuLoad.BALANCED.threads(2) == 1, "balanced on small machines");
		check(MemoryLoad.forHeap(2L << 30) == MemoryLoad.LOW && MemoryLoad.forHeap(6L << 30) == MemoryLoad.MEDIUM && MemoryLoad.forHeap(16L << 30) == MemoryLoad.HIGH, "memory preset by heap");
		check(MemoryLoad.LOW.structureTiles() < MemoryLoad.MEDIUM.structureTiles() && MemoryLoad.MEDIUM.structureTiles() < MemoryLoad.HIGH.structureTiles(), "structure tiles grow with memory");
		check(CpuLoad.parse("HIGH", CpuLoad.LOW) == CpuLoad.HIGH && CpuLoad.parse("nonsense", CpuLoad.LOW) == CpuLoad.LOW, "parsing presets");
	}

	static void scoring() {
		Object group = new Object();
		Focus.set("test:world", new int[] { 0, 0 });
		GenProgress progress = null;
		Probe near = new Probe("near", SectionScheduler.Kind.DISTANT_HORIZONS, group, 100, 0, new ArrayList<>(), new CountDownLatch(0), progress);
		Probe far = new Probe("far", SectionScheduler.Kind.DISTANT_HORIZONS, group, 5000, 0, new ArrayList<>(), new CountDownLatch(0), progress);
		Probe explored = new Probe("explored", SectionScheduler.Kind.MAP_EXPLORED, group, 1000, 0, new ArrayList<>(), new CountDownLatch(0), progress);
		Probe refine = new Probe("refine", SectionScheduler.Kind.MAP_REFINE, group, 1000, 0, new ArrayList<>(), new CountDownLatch(0), progress);
		long now = System.nanoTime();
		check(SectionScheduler.score(near, now) < SectionScheduler.score(far, now), "nearer scores lower");
		check(SectionScheduler.score(explored, now) < SectionScheduler.score(refine, now), "explored squares before refinement at the same distance");
		check(Math.abs(near.distance() - 100.0D) < 1e-9, "distance to a square to the east: " + near.distance());
		check(Focus.distance("test:world", -32, -32, 64) == 0.0D, "inside the square");
		check(Double.isInfinite(Focus.distance("other:world", 0, 0, 64)), "unknown dimension");
		// a long wait makes up for distance: 330 s at 16 blocks per second is 5280 blocks
		check(SectionScheduler.score(far, now + 330_000_000_000L) < SectionScheduler.score(near, now), "aging lets far sections through eventually");
		check(SectionScheduler.score(far, now + 60_000_000_000L) > SectionScheduler.score(near, now), "but not after a minute");
		check(Focus.distanceToSquare(10, 10, 0, 0, 64) == 0.0D && Focus.distanceToSquare(-3, 0, 0, 0, 64) == 3.0D, "square distances");
	}

	static void ordering(SectionScheduler scheduler) throws Exception {
		Object group = new Object();
		List<String> log = Collections.synchronizedList(new ArrayList<>());
		GenProgress progress = new GenProgress("test", "test:world", group, false, () -> 4096);
		Focus.set("test:world", new int[] { 0, 0 });
		// a first job holds the only thread while the others queue up
		CountDownLatch hold = new CountDownLatch(1);
		scheduler.submit(new Probe("first", SectionScheduler.Kind.DISTANT_HORIZONS, group, 0, 0, log, hold, progress));
		Thread.sleep(200L);
		CountDownLatch open = new CountDownLatch(0);
		scheduler.submit(new Probe("far", SectionScheduler.Kind.DISTANT_HORIZONS, group, 3000, 0, log, open, progress));
		scheduler.submit(new Probe("middle", SectionScheduler.Kind.DISTANT_HORIZONS, group, 1000, 0, log, open, progress));
		scheduler.submit(new Probe("near", SectionScheduler.Kind.DISTANT_HORIZONS, group, 200, 0, log, open, progress));
		scheduler.submit(new Probe("map-explored", SectionScheduler.Kind.MAP_EXPLORED, group, 1600, 0, log, open, progress));
		check(progress.waiting() == 4, "four waiting: " + progress.waiting());
		check(!scheduler.shortOfWork(), "three Distant Horizons sections wait for one thread: not short of work");
		check(scheduler.count(group, false, false) == 3 && scheduler.count(group, true, false) == 1, "counts by kind");
		check(Math.abs(scheduler.nearest(group, false) - 0.0D) < 1e-9, "the running section is the nearest pending one");
		// the player moves east before the thread frees up: the far section is now the nearest
		Focus.set("test:world", new int[] { 3000, 0 });
		hold.countDown();
		awaitCount(log, 5);
		check(log.get(0).equals("first"), "first ran first");
		check(log.get(1).equals("far"), "the order follows the player: " + log);
		check(log.indexOf("map-explored") < log.indexOf("middle"), "explored map squares are weighted ahead: " + log);
		check(log.get(4).equals("near"), "the section the player left behind runs last: " + log);
		check(progress.finished() == 5 && progress.waiting() == 0 && progress.running() == 0, "progress counted every section: " + progress.summary());
		check(scheduler.shortOfWork(), "nothing waits: short of work, so Distant Horizons may keep asking");
		progress.close();
	}

	/** Thread priority: normal, lower only while the server is behind (automatic), or the preset's (1.3.0). */
	static void priorities() {
		int normal = Thread.NORM_PRIORITY;
		GlobeConfig.ThreadPriority auto = GlobeConfig.ThreadPriority.AUTO, plain = GlobeConfig.ThreadPriority.NORMAL, lower = GlobeConfig.ThreadPriority.LOWER;
		for (CpuLoad load : new CpuLoad[] { CpuLoad.BALANCED, CpuLoad.HIGH, CpuLoad.EXTREME }) {
			check(SectionScheduler.priorityFor(load, auto, true, false) == normal, load + ": normal while the server keeps up");
			check(SectionScheduler.priorityFor(load, auto, true, true) < normal, load + ": lower while the server is behind");
			check(SectionScheduler.priorityFor(load, auto, false, true) == normal, load + ": stays normal with Server tps priority off");
			check(SectionScheduler.priorityFor(load, plain, true, true) == normal, load + ": Normal is always normal");
			check(SectionScheduler.priorityFor(load, lower, true, false) == load.priority(), load + ": Lower is the preset's priority, as in 1.3.0");
		}
		check(SectionScheduler.priorityFor(CpuLoad.BALANCED, auto, true, true) == CpuLoad.BALANCED.priority(), "balanced drops to its 1.3.0 priority");
		for (GlobeConfig.ThreadPriority mode : GlobeConfig.ThreadPriority.values()) {
			check(SectionScheduler.priorityFor(CpuLoad.MINIMAL, mode, true, false) == Thread.MIN_PRIORITY, "minimal always trickles: " + mode);
			check(SectionScheduler.priorityFor(CpuLoad.LOW, mode, true, false) == CpuLoad.LOW.priority(), "low always runs low: " + mode);
		}
	}

	static void cancelling(SectionScheduler scheduler) throws Exception {
		Object group = new Object(), other = new Object();
		List<String> log = Collections.synchronizedList(new ArrayList<>());
		GenProgress progress = new GenProgress("cancel", "test:world", group, false, () -> 0);
		Focus.set("test:world", new int[] { 0, 0 });
		CountDownLatch hold = new CountDownLatch(1);
		scheduler.submit(new Probe("blocker", SectionScheduler.Kind.DISTANT_HORIZONS, other, 0, 0, log, hold, null));
		Thread.sleep(200L);
		CountDownLatch open = new CountDownLatch(0);
		Probe unwanted = new Probe("unwanted", SectionScheduler.Kind.DISTANT_HORIZONS, group, 100, 0, log, open, progress);
		Probe kept = new Probe("kept", SectionScheduler.Kind.DISTANT_HORIZONS, other, 200, 0, log, open, null);
		Probe grouped1 = new Probe("grouped1", SectionScheduler.Kind.MAP_HOLE, group, 300, 0, log, open, progress);
		Probe grouped2 = new Probe("grouped2", SectionScheduler.Kind.MAP_HOLE, group, 400, 0, log, open, progress);
		scheduler.submit(unwanted);
		scheduler.submit(kept);
		scheduler.submit(grouped1);
		scheduler.submit(grouped2);
		unwanted.cancelled.set(true);
		int dropped = scheduler.cancel(group);
		check(dropped == 3, "cancel drops every waiting job of the group: " + dropped);
		check(unwanted.dropped && grouped1.dropped && grouped2.dropped && !kept.dropped, "dropped jobs are told");
		hold.countDown();
		awaitCount(log, 2);
		check(log.equals(List.of("blocker", "kept")), "only the other group's jobs ran: " + log);
		check(progress.waiting() == 0, "dropped jobs no longer wait");
		progress.close();
	}

	static void stopping(SectionScheduler scheduler) throws Exception {
		Object group = new Object();
		AtomicInteger rows = new AtomicInteger();
		AtomicBoolean stop = new AtomicBoolean();
		AtomicBoolean told = new AtomicBoolean();
		GenProgress progress = new GenProgress("stop", "test:world", group, false, () -> 0);
		scheduler.submit(new SectionScheduler.Job(SectionScheduler.Kind.DISTANT_HORIZONS, group, "test:world", 0, 0, 0) {
			@Override
			public boolean cancelled() {
				return stop.get();
			}

			@Override
			protected void execute() throws Exception {
				// like a sampler checking between rows of columns
				for (int row = 0; row < 1000; row++) {
					if (this.cancelled()) throw new java.util.concurrent.CancellationException();
					rows.incrementAndGet();
					Thread.sleep(5L);
				}
			}

			@Override
			protected void dropped() {
				told.set(true);
			}

			@Override
			protected GenProgress progress() {
				return progress;
			}
		});
		Thread.sleep(100L);
		check(!scheduler.awaitIdle(group, 50L), "the job is still running");
		stop.set(true);
		check(scheduler.awaitIdle(group, 2000L), "a cancelled job stops part-way");
		check(rows.get() < 1000, "it did not run to the end: " + rows.get());
		check(told.get(), "a job stopped part-way is told it ends without a result");
		check(progress.finished() == 0 && progress.running() == 0, "a stopped job is not counted as finished");
		progress.close();
	}

	static void awaitCount(List<String> log, int count) throws InterruptedException {
		long deadline = System.nanoTime() + 10_000_000_000L;
		while (log.size() < count && System.nanoTime() < deadline) Thread.sleep(10L);
		check(log.size() >= count, "expected " + count + " jobs to run, got " + log);
	}
}
