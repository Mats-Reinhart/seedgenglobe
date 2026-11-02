package dev.seedgenglobe.gen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.Log;

/**
 * SeedGenGlobe's own worker threads and the one queue every LOD section goes through: sections Distant Horizons asks
 * for (on the server, in singleplayer and on multiplayer clients) and sections the Xaero LOD map computes for itself.
 * <p>
 * A worker always takes the waiting section closest to a player of its dimension (see {@link Focus}), weighted by
 * what it is for, so holes and coarse terrain next to the player are fixed before generation expands outward, and a
 * section that waits gains a little priority every second so nothing starves. Distances are worked out when a worker
 * takes a section, so the order follows the player as they move.
 * <p>
 * The number of threads comes from the CPU load preset (or {@code generatorThreads}). While the server falls behind
 * its 20 ticks per second, or the heap runs low, fewer sections run at once (see {@link #maintain}); the threads
 * themselves stay, so speed comes back as soon as the game can spare it.
 */
public final class SectionScheduler {

	/** What a section is computed for. The weight multiplies its distance to the player: lower goes first. */
	public enum Kind {
		DISTANT_HORIZONS("Distant Horizons", 1.0D),
		CLIENT("Distant Horizons (client)", 1.0D),
		MAP_EXPLORED("map next to explored chunks", 0.5D),
		MAP_NEAR("map near the player", 0.75D),
		MAP_HOLE("map gap", 1.0D),
		MAP_REFINE("map refinement", 1.5D);

		public final String label;
		final double weight;

		Kind(String label, double weight) {
			this.label = label;
			this.weight = weight;
		}

		public boolean forMap() {
			return this.ordinal() >= MAP_EXPLORED.ordinal();
		}
	}

	/** One section to compute. Subclasses say what to do with it and when it is no longer wanted. */
	public abstract static class Job {

		public final Kind kind;
		/** What the job belongs to (a generator, the map builder): {@link #cancel} drops a whole group. */
		public final Object group;
		/** {@link Focus} key of the dimension: the level's identifier. */
		public final String dimension;
		public final int originX, originZ, detail;
		final long queuedAt = System.nanoTime();
		volatile long startedAt;

		protected Job(Kind kind, Object group, String dimension, int originX, int originZ, int detail) {
			this.kind = kind;
			this.group = group;
			this.dimension = dimension;
			this.originX = originX;
			this.originZ = originZ;
			this.detail = detail;
		}

		/** Width of the section in blocks. */
		public final long span() {
			return 64L << Math.min(this.detail, 40);
		}

		/** Distance from the nearest player of its dimension, or infinity when none is known. */
		public final double distance() {
			return Focus.distance(this.dimension, this.originX, this.originZ, this.span());
		}

		public final boolean isStarted() {
			return this.startedAt != 0L;
		}

		/** Seconds this job has run, or 0 before it started. */
		public final double runningSeconds() {
			long started = this.startedAt;
			return started == 0L ? 0.0D : (System.nanoTime() - started) / 1.0e9D;
		}

		/** Whether the job is no longer wanted. Checked before it starts and, by the job itself, while it runs. */
		public abstract boolean cancelled();

		/** Computes the section on a worker thread. Throwing {@link CancellationException} reports a cancellation. */
		protected abstract void execute() throws Exception;

		/** The job ends without a result: dropped before it ran, or stopped part-way because it was cancelled. */
		protected void dropped() {}

		/** {@link #execute} failed with something other than a cancellation (already logged). */
		protected void failed(Throwable throwable) {}

		/** Progress counters this job reports to, or null. */
		protected @Nullable GenProgress progress() {
			return null;
		}

		@Override
		public String toString() {
			return this.kind.label + " section d" + this.detail + " at " + this.originX + ", " + this.originZ;
		}
	}

	/** Counts of waiting and running sections by kind, and what limits them right now. */
	public record Snapshot(int threads, int allowed, String limitedBy, Map<Kind, Integer> waiting, Map<Kind, Integer> running) {

		public int waiting(boolean map) {
			int sum = 0;
			for (Map.Entry<Kind, Integer> entry : this.waiting.entrySet()) {
				if (entry.getKey().forMap() == map) sum += entry.getValue();
			}
			return sum;
		}

		public int running(boolean map) {
			int sum = 0;
			for (Map.Entry<Kind, Integer> entry : this.running.entrySet()) {
				if (entry.getKey().forMap() == map) sum += entry.getValue();
			}
			return sum;
		}
	}

	/** Blocks of distance a waiting section makes up for every second it waits. */
	static final double AGING_BLOCKS_PER_SECOND = 16.0D;
	/** Distance used for sections in a dimension without a known player: they go in the order they came. */
	static final double UNKNOWN_DISTANCE = 1.0e9D;

	private static final SectionScheduler INSTANCE = new SectionScheduler();

	public static SectionScheduler get() {
		return INSTANCE;
	}

	private final Object lock = new Object();
	private final ArrayList<Job> waiting = new ArrayList<>();
	private final ArrayList<Job> running = new ArrayList<>();
	private final List<Worker> workers = new ArrayList<>();
	private final AtomicInteger workerIds = new AtomicInteger();
	private int threads, priority = Thread.NORM_PRIORITY - 2;
	/** Sections allowed to run at once: the thread count, lowered while the server or the heap struggle. */
	private int allowed;
	private int throttle = Integer.MAX_VALUE;
	private String limitedBy = "";
	private final Map<Kind, Integer> failuresLogged = new EnumMap<>(Kind.class);

	private SectionScheduler() {}

	// ------------------------------------------------------------------------------------------------ setup

	/** Applies the thread count and priority from the config: starts or retires threads as needed. */
	public void configure() {
		GlobeConfig config = GlobeConfig.get();
		int wanted = config.generatorThreads > 0 ? config.generatorThreads : config.cpuLoad.threads();
		int priority = config.cpuLoad.priority();
		synchronized (this.lock) {
			boolean changed = wanted != this.threads || priority != this.priority;
			this.priority = priority;
			this.threads = wanted;
			int alive = 0;
			for (Worker worker : this.workers) {
				if (worker.retire) continue;
				if (alive < wanted) {
					worker.setPriority(priority);
					alive++;
				}
				else {
					worker.retire = true;
				}
			}
			while (alive < wanted) {
				Worker worker = new Worker(this.workerIds.incrementAndGet(), priority);
				this.workers.add(worker);
				worker.start();
				alive++;
			}
			this.workers.removeIf(worker -> worker.retire && !worker.isAlive());
			this.updateAllowed();
			this.lock.notifyAll();
			if (changed) {
				Log.LOGGER.info(
					"SeedGenGlobe generates LOD sections on {} ({}{}), nearest to the player first.",
					wanted == 1 ? "1 thread" : wanted + " threads",
					config.generatorThreads > 0 ? "generatorThreads=" + config.generatorThreads : "CPU load " + config.cpuLoad.label(),
					priority == Thread.NORM_PRIORITY ? ", normal priority" : ", priority " + priority
				);
			}
		}
	}

	/** Applies changed settings, unless no section was generated yet (then the first one sets the scheduler up). */
	public void reconfigureIfRunning() {
		if (this.threads() > 0) this.configure();
	}

	public int threads() {
		synchronized (this.lock) {
			return this.threads;
		}
	}

	/** Sections that may run at once right now. */
	public int allowed() {
		synchronized (this.lock) {
			return this.allowed;
		}
	}

	/**
	 * How many sections Distant Horizons should keep in SeedGenGlobe's hands at once: every thread busy, plus a few
	 * waiting per thread (memory preset) so the nearest-first order has something to choose from.
	 */
	public int capacity() {
		int threads = Math.max(1, this.threads());
		return threads + threads * GlobeConfig.get().memoryLoad.queuePerThread();
	}

	// ------------------------------------------------------------------------------------------------ jobs

	public void submit(Job job) {
		// the first job may come before anyone configured the scheduler: use the config as it is
		if (this.threads() <= 0) this.configure();
		GenProgress progress = job.progress();
		if (progress != null) progress.queued();
		synchronized (this.lock) {
			this.waiting.add(job);
			this.lock.notify();
		}
	}

	/** Drops every waiting job of a group; running ones notice through {@link Job#cancelled()}. @return jobs dropped */
	public int cancel(Object group) {
		List<Job> dropped = new ArrayList<>();
		synchronized (this.lock) {
			this.waiting.removeIf(job -> {
				if (job.group != group) return false;
				dropped.add(job);
				return true;
			});
		}
		for (Job job : dropped) this.drop(job);
		return dropped.size();
	}

	/** Waits until no job of the group runs any more. @return whether that happened within the timeout */
	public boolean awaitIdle(Object group, long timeoutMillis) {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		synchronized (this.lock) {
			while (true) {
				boolean busy = false;
				for (Job job : this.running) {
					if (job.group == group) {
						busy = true;
						break;
					}
				}
				if (!busy) return true;
				long left = deadline - System.nanoTime();
				if (left <= 0L) return false;
				try {
					this.lock.wait(Math.max(1L, left / 1_000_000L));
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
	}

	private void drop(Job job) {
		GenProgress progress = job.progress();
		if (progress != null) progress.dropped();
		try {
			job.dropped();
		}
		catch (RuntimeException exception) {
			Log.LOGGER.debug("SeedGenGlobe: dropping {} failed", job, exception);
		}
	}

	/** The job a worker should run next, or null when the worker is to stop. Blocks while there is nothing to do. */
	private @Nullable Job take(Worker worker) {
		while (true) {
			List<Job> dropped = new ArrayList<>();
			Job chosen = null;
			boolean retire = false;
			synchronized (this.lock) {
				while (true) {
					if (worker.retire) {
						retire = true;
						break;
					}
					if (this.running.size() < this.allowed && !this.waiting.isEmpty()) {
						chosen = this.best(dropped);
						if (chosen != null) {
							chosen.startedAt = System.nanoTime();
							this.running.add(chosen);
							break;
						}
					}
					// cancelled jobs are reported outside the lock: completing their futures runs other mods' code
					if (!dropped.isEmpty()) break;
					try {
						this.lock.wait(1_000L);
					}
					catch (InterruptedException interrupted) {
						if (worker.retire) {
							retire = true;
							break;
						}
					}
				}
			}
			for (Job job : dropped) this.drop(job);
			if (retire) {
				if (chosen != null) {
					synchronized (this.lock) {
						this.running.remove(chosen);
						this.waiting.add(chosen);
					}
				}
				return null;
			}
			if (chosen != null) return chosen;
		}
	}

	/** Removes and returns the waiting job to run next; cancelled ones found on the way go into {@code dropped}. */
	private @Nullable Job best(List<Job> dropped) {
		Job best = null;
		int bestIndex = -1;
		double bestScore = Double.POSITIVE_INFINITY;
		long now = System.nanoTime();
		for (int index = 0; index < this.waiting.size(); index++) {
			Job job = this.waiting.get(index);
			boolean cancelled;
			try {
				cancelled = job.cancelled();
			}
			catch (RuntimeException exception) {
				cancelled = true;
			}
			if (cancelled) {
				dropped.add(job);
				this.waiting.remove(index--);
				continue;
			}
			double score = score(job, now);
			if (score < bestScore) {
				bestScore = score;
				best = job;
				bestIndex = index;
			}
		}
		if (best != null) this.waiting.remove(bestIndex);
		return best;
	}

	/** Lower runs first: distance to the player times the kind's weight, minus a bonus for time spent waiting. */
	static double score(Job job, long now) {
		double distance = job.distance();
		if (Double.isInfinite(distance) || Double.isNaN(distance)) distance = UNKNOWN_DISTANCE;
		double waited = (now - job.queuedAt) / 1.0e9D;
		return distance * job.kind.weight - waited * AGING_BLOCKS_PER_SECOND;
	}

	private void finished(Job job) {
		synchronized (this.lock) {
			this.running.remove(job);
			this.lock.notifyAll();
		}
	}

	private void run(Job job) {
		GenProgress progress = job.progress();
		long start = System.nanoTime();
		boolean cancelledBefore;
		try {
			cancelledBefore = job.cancelled();
		}
		catch (RuntimeException exception) {
			cancelledBefore = true;
		}
		if (cancelledBefore) {
			if (progress != null) progress.dropped();
			try {
				job.dropped();
			}
			catch (RuntimeException ignored) {}
			return;
		}
		if (progress != null) progress.started(job);
		try {
			job.execute();
			if (progress != null) progress.finished(job, System.nanoTime() - start);
		}
		catch (CancellationException cancelled) {
			if (progress != null) progress.cancelled(job);
			try {
				job.dropped();
			}
			catch (RuntimeException ignored) {}
		}
		catch (Throwable throwable) {
			if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) {
				if (progress != null) progress.failed(job);
				throw error;
			}
			if (progress != null) progress.failed(job);
			this.logFailure(job, throwable);
			try {
				job.failed(throwable);
			}
			catch (RuntimeException ignored) {}
		}
	}

	private void logFailure(Job job, Throwable throwable) {
		int count;
		synchronized (this.failuresLogged) {
			count = this.failuresLogged.merge(job.kind, 1, Integer::sum);
		}
		if (count <= 3 || Integer.bitCount(count) == 1) {
			Log.LOGGER.warn("SeedGenGlobe: {} failed ({} {} failures so far)", job, count, job.kind.label, throwable);
		}
	}

	// ------------------------------------------------------------------------------------------------ state

	/** Distance from the player to the nearest waiting or running job of a group and kind, or infinity when none. */
	public double nearest(Object group, boolean map) {
		List<Job> jobs = new ArrayList<>();
		synchronized (this.lock) {
			for (Job job : this.waiting) if (job.group == group && job.kind.forMap() == map) jobs.add(job);
			for (Job job : this.running) if (job.group == group && job.kind.forMap() == map) jobs.add(job);
		}
		double best = Double.POSITIVE_INFINITY;
		for (Job job : jobs) best = Math.min(best, job.distance());
		return best;
	}

	/** Whether a job of the group covers or overlaps this square (waiting or running). */
	public boolean overlaps(Object group, boolean map, long minX, long minZ, long span) {
		synchronized (this.lock) {
			for (Job job : this.waiting) if (job.group == group && job.kind.forMap() == map && overlap(job, minX, minZ, span)) return true;
			for (Job job : this.running) if (job.group == group && job.kind.forMap() == map && overlap(job, minX, minZ, span)) return true;
		}
		return false;
	}

	/**
	 * Whether a non-map job of the group (a Distant Horizons section) overlaps this square at this detail level or a
	 * finer one: its result will cover the square at least that well.
	 */
	public boolean covered(Object group, long minX, long minZ, long span, int detailAtMost) {
		synchronized (this.lock) {
			for (Job job : this.waiting) if (job.group == group && !job.kind.forMap() && job.detail <= detailAtMost && overlap(job, minX, minZ, span)) return true;
			for (Job job : this.running) if (job.group == group && !job.kind.forMap() && job.detail <= detailAtMost && overlap(job, minX, minZ, span)) return true;
		}
		return false;
	}

	static boolean overlap(Job job, long minX, long minZ, long span) {
		long jobSpan = job.span();
		return job.originX < minX + span && minX < job.originX + jobSpan && job.originZ < minZ + span && minZ < job.originZ + jobSpan;
	}

	public int count(Object group, boolean map, boolean includeRunning) {
		int count = 0;
		synchronized (this.lock) {
			for (Job job : this.waiting) if (job.group == group && job.kind.forMap() == map) count++;
			if (includeRunning) for (Job job : this.running) if (job.group == group && job.kind.forMap() == map) count++;
		}
		return count;
	}

	public Snapshot snapshot() {
		Map<Kind, Integer> waiting = new EnumMap<>(Kind.class), running = new EnumMap<>(Kind.class);
		synchronized (this.lock) {
			for (Job job : this.waiting) waiting.merge(job.kind, 1, Integer::sum);
			for (Job job : this.running) running.merge(job.kind, 1, Integer::sum);
			return new Snapshot(this.threads, this.allowed, this.limitedBy, waiting, running);
		}
	}

	/** One line about the threads, for logs and the status screen. */
	public String describe() {
		Snapshot snapshot = this.snapshot();
		StringBuilder line = new StringBuilder();
		line.append(snapshot.threads()).append(snapshot.threads() == 1 ? " SeedGenGlobe thread (" : " SeedGenGlobe threads (")
			.append(snapshot.running(false) + snapshot.running(true)).append(" busy, ")
			.append(snapshot.waiting(false) + snapshot.waiting(true)).append(" waiting");
		if (snapshot.allowed() < snapshot.threads()) {
			line.append(", ").append(snapshot.allowed()).append(" allowed: ").append(snapshot.limitedBy());
		}
		return line.append(')').toString();
	}

	// ------------------------------------------------------------------------------------------------ throttling

	/**
	 * Adjusts how many sections may run at once, once a second (from the progress reporter): one fewer every second
	 * while the server is behind (down to a third of the threads), one more every second while it keeps up, and at
	 * most half the threads, or one, while the heap is nearly full.
	 */
	public void maintain() {
		GlobeConfig config = GlobeConfig.get();
		MemoryWatch.update();
		synchronized (this.lock) {
			int threads = this.threads;
			if (threads <= 0) return;
			int floor = Math.max(1, (threads + 2) / 3);
			if (config.yieldToServer && ServerLoad.behind()) {
				this.throttle = Math.max(floor, Math.min(this.throttle, threads) - 1);
			}
			else if (this.throttle < threads) {
				this.throttle = ServerLoad.keepingUp() ? this.throttle + 1 : this.throttle;
			}
			else {
				this.throttle = Integer.MAX_VALUE;
			}
			this.updateAllowed();
			this.workers.removeIf(worker -> worker.retire && !worker.isAlive());
			this.lock.notifyAll();
		}
	}

	private void updateAllowed() {
		int threads = this.threads;
		int allowed = threads;
		String reason = "";
		if (this.throttle < allowed) {
			allowed = this.throttle;
			reason = String.format(java.util.Locale.ROOT, "the server is behind (%.0f ms per tick)", ServerLoad.averageMillis());
		}
		MemoryWatch.Pressure pressure = MemoryWatch.pressure();
		if (pressure == MemoryWatch.Pressure.CRITICAL && allowed > 1) {
			allowed = 1;
			reason = "the heap is almost full";
		}
		else if (pressure == MemoryWatch.Pressure.HIGH && allowed > Math.max(1, threads / 2)) {
			allowed = Math.max(1, threads / 2);
			reason = "the heap is getting full";
		}
		this.allowed = Math.max(threads > 0 ? 1 : 0, allowed);
		this.limitedBy = reason;
	}

	// ------------------------------------------------------------------------------------------------ workers

	private final class Worker extends Thread {

		volatile boolean retire;

		Worker(int id, int priority) {
			super("SeedGenGlobe Generator " + id);
			this.setDaemon(true);
			this.setPriority(priority);
		}

		@Override
		public void run() {
			while (true) {
				Job job = SectionScheduler.this.take(this);
				if (job == null) return;
				try {
					SectionScheduler.this.run(job);
				}
				catch (Throwable throwable) {
					if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) {
						SectionScheduler.this.finished(job);
						throw error;
					}
					Log.LOGGER.warn("SeedGenGlobe: a generator thread hit an error", throwable);
				}
				finally {
					SectionScheduler.this.finished(job);
				}
			}
		}
	}
}
