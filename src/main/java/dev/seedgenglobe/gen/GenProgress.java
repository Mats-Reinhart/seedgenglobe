package dev.seedgenglobe.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.Log;

/**
 * Progress of one producer of LOD sections: a dimension's Distant Horizons generator, or the Xaero LOD map. Counts
 * sections as they are queued, started, finished, cancelled or failed, times the phases of each section, and knows how
 * far out from the player everything is done (the distance of the nearest section still waiting or running).
 * <p>
 * The log gets a line when the first section starts, when generation goes quiet and when it starts again, a summary
 * every {@code statsLogSeconds} while it runs (sections started as well as finished, so work that has begun shows
 * before it is done), and, with {@code logSections}, a line for every section as it starts and finishes. The same
 * moments are offered to {@link #LISTENERS} (the client turns them into chat messages) and the numbers feed the
 * progress overlay and the status screen.
 */
public final class GenProgress {

	/** Parts of a section's work, timed separately. */
	public enum Phase {
		STRUCTURE_LOOKUP("structure lookup"),
		TERRAIN("terrain"),
		STRUCTURE_TERRAIN("structure terrain"),
		STRUCTURES("structures"),
		TREES("trees"),
		GROUND_COVER("ground cover"),
		WRITING("writing"),
		MAP("map");

		public final String label;

		Phase(String label) {
			this.label = label;
		}
	}

	/** Moments worth telling the player about. */
	public enum EventType { STARTED, RESUMED, MILESTONE, DONE }

	public record Event(GenProgress progress, EventType type, double radius, String message) {}

	/** Every progress object that may still report, for the reporter thread, the overlay and the status screen. */
	static final Set<GenProgress> LIVE = new CopyOnWriteArraySet<>();
	/** Called on the reporter thread (or a generator thread for STARTED). */
	public static final List<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

	/**
	 * Generation counts as quiet once nothing waited or ran for this long. Distant Horizons asks for a few sections
	 * now and then while the player walks around, which should not read as stopping and starting every time.
	 */
	static final long IDLE_NANOS = 20_000_000_000L;

	public final String name;
	/** The Xaero LOD map rather than a Distant Horizons generator (changes the wording). */
	public final boolean map;
	/** {@link Focus} key of the dimension. */
	public final String dimension;
	/** The scheduler group of this producer's jobs, for "complete out to". */
	private volatile Object group;
	/** How far out the work goes (render distance or map radius), 0 when unknown. */
	private final Supplier<Integer> targetRadius;
	private final long createdAt = System.nanoTime();

	final LongAdder queued = new LongAdder(), started = new LongAdder(), finished = new LongAdder();
	final LongAdder cancelled = new LongAdder(), dropped = new LongAdder(), failed = new LongAdder();
	final LongAdder columns = new LongAdder(), columnErrors = new LongAdder(), busyNanos = new LongAdder();
	final LongAdder[] phaseNanos = new LongAdder[Phase.values().length];
	final AtomicLongArray perDetail = new AtomicLongArray(16);
	final AtomicLong slowestNanos = new AtomicLong();
	final AtomicInteger running = new AtomicInteger(), waiting = new AtomicInteger();
	private final AtomicBoolean announced = new AtomicBoolean();
	private volatile long lastActivityAt;
	private volatile boolean closed;

	// sections running at once, integrated over time
	private final Object integratorLock = new Object();
	private long integratedAt = System.nanoTime();
	private double integrated;

	// finished sections per second, for the current rate
	private final long[] buckets = new long[64];
	private long bucketSecond = Long.MIN_VALUE;

	// reporter thread state
	private long reportedAt = System.nanoTime(), reportedStarted, reportedFinished, reportedBusy;
	private double reportedIntegrated;
	private final long[] reportedPhases = new long[Phase.values().length];
	private boolean active, everActive;
	private long activeSince;
	private int milestone;
	private volatile double completeRadius;

	public GenProgress(String name, String dimension, Object group, boolean map, Supplier<Integer> targetRadius) {
		this.name = name;
		this.dimension = dimension;
		this.group = group;
		this.map = map;
		this.targetRadius = targetRadius;
		for (int i = 0; i < this.phaseNanos.length; i++) this.phaseNanos[i] = new LongAdder();
		LIVE.add(this);
		ProgressReporter.start();
	}

	public void setGroup(Object group) {
		this.group = group;
	}

	public Object group() {
		return this.group;
	}

	/**
	 * Stops reporting (the generator closed) and lets go of the group: the generator or the map's target, which hold the
	 * level and its terrain, so nothing that still knows this progress keeps those in memory.
	 */
	public void close() {
		this.closed = true;
		this.group = null;
		LIVE.remove(this);
	}

	public boolean isClosed() {
		return this.closed;
	}

	public static List<GenProgress> live() {
		return new ArrayList<>(LIVE);
	}

	// ------------------------------------------------------------------------------------------------ counting

	void queued() {
		this.queued.increment();
		this.waiting.incrementAndGet();
		this.lastActivityAt = System.nanoTime();
	}

	void started(SectionScheduler.Job job) {
		this.waiting.decrementAndGet();
		this.integrate(+1);
		this.started.increment();
		long now = System.nanoTime();
		this.lastActivityAt = now;
		if (this.announced.compareAndSet(false, true)) {
			String message = String.format(
				Locale.ROOT,
				"%s: %s after %.1f s, on %s",
				this.title(),
				this.map ? "computing the first map sections" : "Distant Horizons asked for the first sections",
				(now - this.createdAt) / 1.0e9D,
				SectionScheduler.get().describe()
			);
			Log.LOGGER.info("{}", message);
		}
		if (GlobeConfig.get().logSections) {
			double distance = job.distance();
			Log.LOGGER.info(
				"{}: started {} ({} from the player, {} waiting)",
				this.title(), job, Double.isInfinite(distance) ? "?" : GlobeConfig.ProgressUnit.current().format(distance), this.waiting.get()
			);
		}
	}

	void finished(SectionScheduler.Job job, long nanos) {
		this.integrate(-1);
		this.finished.increment();
		this.busyNanos.add(nanos);
		this.slowestNanos.accumulateAndGet(nanos, Math::max);
		if (job.detail >= 0 && job.detail < 16) this.perDetail.incrementAndGet(job.detail);
		this.lastActivityAt = System.nanoTime();
		this.countFinished();
		if (GlobeConfig.get().logSections) {
			Log.LOGGER.info("{}: finished {} in {} ms", this.title(), job, String.format(Locale.ROOT, "%.0f", nanos / 1.0e6D));
		}
	}

	void cancelled(SectionScheduler.Job job) {
		this.integrate(-1);
		this.cancelled.increment();
		this.lastActivityAt = System.nanoTime();
	}

	void failed(SectionScheduler.Job job) {
		this.integrate(-1);
		this.failed.increment();
		this.lastActivityAt = System.nanoTime();
	}

	/** Dropped before it ran. */
	void dropped() {
		this.waiting.decrementAndGet();
		this.dropped.increment();
	}

	/** Columns written and the time each phase took, for one section (called by the generator). */
	public void recordSection(int columnCount, int errorCount, long[] phases) {
		this.columns.add(columnCount);
		if (errorCount > 0) this.columnErrors.add(errorCount);
		for (int i = 0; i < Math.min(phases.length, this.phaseNanos.length); i++) {
			if (phases[i] != 0L) this.phaseNanos[i].add(phases[i]);
		}
	}

	private void integrate(int delta) {
		synchronized (this.integratorLock) {
			long now = System.nanoTime();
			int level = this.running.get();
			this.integrated += level * ((now - this.integratedAt) / 1.0e9D);
			this.integratedAt = now;
			this.running.addAndGet(delta);
		}
	}

	private double integratedNow() {
		synchronized (this.integratorLock) {
			long now = System.nanoTime();
			return this.integrated + this.running.get() * ((now - this.integratedAt) / 1.0e9D);
		}
	}

	private void countFinished() {
		long second = System.nanoTime() / 1_000_000_000L;
		synchronized (this.buckets) {
			this.advance(second);
			this.buckets[(int)(second & 63)]++;
		}
	}

	private void advance(long second) {
		if (this.bucketSecond == Long.MIN_VALUE || second - this.bucketSecond >= 64) {
			java.util.Arrays.fill(this.buckets, 0L);
		}
		else {
			for (long s = this.bucketSecond + 1; s <= second; s++) this.buckets[(int)(s & 63)] = 0L;
		}
		this.bucketSecond = Math.max(this.bucketSecond, second);
	}

	// ------------------------------------------------------------------------------------------------ reading

	/** Sections finished per second over the last 30 seconds. */
	public double rate() {
		long second = System.nanoTime() / 1_000_000_000L;
		long sum = 0L;
		synchronized (this.buckets) {
			this.advance(second);
			for (int i = 1; i <= 30; i++) sum += this.buckets[(int)((second - i) & 63)];
		}
		return sum / 30.0D;
	}

	public long finished() {
		return this.finished.sum();
	}

	public long started() {
		return this.started.sum();
	}

	public int running() {
		return Math.max(0, this.running.get());
	}

	public int waiting() {
		return Math.max(0, this.waiting.get());
	}

	/** Milliseconds per section, on average since the start. */
	public double averageMillis() {
		long finished = this.finished.sum();
		return finished == 0L ? 0.0D : this.busyNanos.sum() / 1.0e6D / finished;
	}

	/** Sections are waiting or running, or were within the last few seconds. */
	public boolean busy() {
		return this.running() > 0 || this.waiting() > 0 || System.nanoTime() - this.lastActivityAt < IDLE_NANOS;
	}

	/**
	 * How far from the player everything this producer was asked for is done: the distance of the nearest section
	 * still waiting or running, or the full radius while nothing is pending. 0 before anything happened.
	 */
	public double completeRadius() {
		return this.completeRadius;
	}

	/** How far out the work goes, 0 when unknown. */
	public int targetRadius() {
		try {
			Integer radius = this.targetRadius.get();
			return radius != null ? Math.max(0, radius) : 0;
		}
		catch (RuntimeException | LinkageError error) {
			return 0;
		}
	}

	public String title() {
		return "SeedGenGlobe [" + this.name + "]";
	}

	/** Totals since the start, for the status screen and {@code /seedgenglobe debug status}. */
	public String summary() {
		long finished = this.finished.sum();
		long columns = this.columns.sum();
		double busyMs = this.busyNanos.sum() / 1.0e6D;
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
			"%,d sections done (%,d started, %,d cancelled, %,d failed), %,d columns, %.0f ms per section (slowest %.0f ms), %.2f per second lately, %d running, %d waiting, %d column errors%s%s",
			finished,
			this.started.sum(),
			this.cancelled.sum() + this.dropped.sum(),
			this.failed.sum(),
			columns,
			finished == 0 ? 0.0D : busyMs / finished,
			this.slowestNanos.get() / 1.0e6D,
			this.rate(),
			this.running(),
			this.waiting(),
			this.columnErrors.sum(),
			detail.length() > 0 ? " [" + detail + "]" : "",
			this.radiusText().isEmpty() ? "" : "; " + this.radiusText()
		);
	}

	/** "complete out to 128 of 1,048 chunks" (or blocks, see {@code progressUnit}), or empty while unknown. */
	public String radiusText() {
		double complete = this.completeRadius;
		if (complete <= 0.0D) return "";
		int target = this.targetRadius();
		GlobeConfig.ProgressUnit unit = GlobeConfig.ProgressUnit.current();
		if (target > 0 && complete >= target) return "complete out to " + unit.format(target);
		if (target > 0) return "complete out to " + unit.format(complete, target);
		return "complete out to " + unit.format(complete);
	}

	// ------------------------------------------------------------------------------------------------ reporting

	/** Once a second on the reporter thread: radius, quiet/resumed lines, milestones and the periodic summary. */
	void report(long now) {
		int target = this.targetRadius();
		boolean pending = this.running() > 0 || this.waiting() > 0;
		boolean recent = now - this.lastActivityAt < IDLE_NANOS;
		Object group = this.group;
		double nearest = group != null ? SectionScheduler.get().nearest(group, this.map) : Double.POSITIVE_INFINITY;
		if (pending && !Double.isInfinite(nearest)) {
			this.completeRadius = nearest;
		}
		else if (!pending && !recent && this.everActive) {
			this.completeRadius = target > 0 ? target : Math.max(this.completeRadius, 1.0D);
		}
		if (pending || recent) {
			if (!this.active) {
				this.active = true;
				this.activeSince = now;
				if (this.everActive) {
					String message = String.format(
						Locale.ROOT, "%s: %s again%s",
						this.title(),
						this.map ? "computing map sections" : "generating",
						Double.isInfinite(nearest) ? "" : ", the nearest section " + GlobeConfig.ProgressUnit.current().format(nearest) + " away"
					);
					Log.LOGGER.info("{}", message);
					fire(new Event(this, EventType.RESUMED, nearest, message));
				}
				else {
					fire(new Event(this, EventType.STARTED, nearest, this.title() + ": started"));
				}
				this.everActive = true;
				this.milestone = 0;
			}
			this.milestones(target);
		}
		else if (this.active) {
			this.active = false;
			this.logSummary(now, true);
			String message = String.format(
				Locale.ROOT,
				"%s: %s (%,d sections this session, %s); %s",
				this.title(),
				this.map ? "the map has every section it wants for now" : "Distant Horizons wants no more sections for now",
				this.finished.sum(),
				duration(now - this.activeSince),
				this.radiusText().isEmpty() ? "nothing pending" : this.radiusText()
			);
			Log.LOGGER.info("{}", message);
			fire(new Event(this, EventType.DONE, this.completeRadius, message));
			return;
		}
		int seconds = GlobeConfig.get().statsLogSeconds;
		if (this.active && seconds > 0 && now - this.reportedAt >= seconds * 1_000_000_000L) {
			this.logSummary(now, false);
		}
	}

	/** Every doubling of the complete radius from 1,024 blocks (64 chunks) on is a milestone. */
	private void milestones(int target) {
		double complete = this.completeRadius;
		int reached = 0;
		for (int radius = 1024; radius <= complete && reached < 20; radius <<= 1) reached++;
		if (reached > this.milestone) {
			this.milestone = reached;
			long radius = 1024L << (reached - 1);
			if (target > 0 && radius >= target) return; // the DONE event says it
			String message = this.title() + ": complete out to " + GlobeConfig.ProgressUnit.current().format(radius);
			Log.LOGGER.info("{}", message);
			fire(new Event(this, EventType.MILESTONE, radius, message));
		}
	}

	private void logSummary(long now, boolean last) {
		long started = this.started.sum(), finished = this.finished.sum(), busy = this.busyNanos.sum();
		long newStarted = started - this.reportedStarted, newFinished = finished - this.reportedFinished;
		double wall = Math.max((now - this.reportedAt) / 1.0e9D, 1.0e-3D);
		double integrated = this.integratedNow();
		double atOnce = (integrated - this.reportedIntegrated) / wall;
		long[] phases = new long[this.phaseNanos.length];
		long phaseTotal = 0L;
		for (int i = 0; i < phases.length; i++) {
			long sum = this.phaseNanos[i].sum();
			phases[i] = sum - this.reportedPhases[i];
			this.reportedPhases[i] = sum;
			phaseTotal += phases[i];
		}
		this.reportedAt = now;
		this.reportedStarted = started;
		this.reportedFinished = finished;
		this.reportedIntegrated = integrated;
		long newBusy = busy - this.reportedBusy;
		this.reportedBusy = busy;
		if (newStarted == 0 && newFinished == 0 && last) return;
		StringBuilder line = new StringBuilder();
		line.append(String.format(
			Locale.ROOT,
			"%s: last %.0f s: %d started, %d finished (%.1f per second), %.1f at a time",
			this.title(), wall, newStarted, newFinished, newFinished / wall, atOnce
		));
		if (newFinished > 0) {
			line.append(String.format(Locale.ROOT, ", %.0f ms per section", newBusy / 1.0e6D / newFinished));
			if (phaseTotal > 0) {
				line.append(" (");
				boolean first = true;
				for (Phase phase : Phase.values()) {
					long nanos = phases[phase.ordinal()];
					if (nanos * 50 < phaseTotal) continue; // under 2%
					if (!first) line.append(", ");
					first = false;
					line.append(phase.label).append(' ').append(String.format(Locale.ROOT, "%.0f", nanos / 1.0e6D / newFinished)).append(" ms");
				}
				line.append(')');
			}
		}
		line.append(String.format(Locale.ROOT, "; %,d done in total, %d running, %d waiting", finished, this.running(), this.waiting()));
		String radius = this.radiusText();
		if (!radius.isEmpty()) line.append("; ").append(radius);
		line.append("; ").append(SectionScheduler.get().describe());
		if (!this.map) {
			String pipeline = DhPipelineView.describe();
			if (pipeline != null) line.append(". ").append(pipeline);
		}
		Log.LOGGER.info("{}", line);
	}

	static String duration(long nanos) {
		double seconds = nanos / 1.0e9D;
		if (seconds < 120.0D) return String.format(Locale.ROOT, "%.0f s", seconds);
		if (seconds < 7200.0D) return String.format(Locale.ROOT, "%.1f min", seconds / 60.0D);
		return String.format(Locale.ROOT, "%.1f h", seconds / 3600.0D);
	}

	static void fire(Event event) {
		for (Consumer<Event> listener : LISTENERS) {
			try {
				listener.accept(event);
			}
			catch (RuntimeException exception) {
				Log.LOGGER.debug("SeedGenGlobe: a progress listener failed", exception);
			}
		}
	}

	/** Lets the Distant Horizons side describe its queues without this package depending on Distant Horizons. */
	public static final class DhPipelineView {

		private static volatile @Nullable Supplier<String> describer;

		private DhPipelineView() {}

		public static void set(@Nullable Supplier<String> supplier) {
			describer = supplier;
		}

		static @Nullable String describe() {
			Supplier<String> supplier = describer;
			if (supplier == null) return null;
			try {
				return supplier.get();
			}
			catch (RuntimeException | LinkageError error) {
				return null;
			}
		}
	}
}
