package dev.seedgenglobe.gen;

import java.util.Locale;

/**
 * How much of the CPU SeedGenGlobe may use for LOD sections and the Xaero LOD map, like Distant Horizons' own CPU
 * load setting. Each preset picks a thread count from the number of cores and a thread priority.
 * <p>
 * The 1.2.0 stress test (8 cores, 16 threads) generated about two sections per second whether 11 or 15 threads ran
 * them: past the physical cores, more threads only made every section slower and the game laggier. The presets
 * therefore count physical cores, which Java cannot see; with 8 or more logical CPUs, half of them are taken to be
 * hyper-threads.
 */
public enum CpuLoad {

	/** One thread at the lowest priority: generation trickles along without being noticed. */
	MINIMAL,
	/** A quarter of the cores, low priority. */
	LOW,
	/** All physical cores but two. The default. */
	BALANCED,
	/** Most logical CPUs. */
	HIGH,
	/** Every logical CPU but one: fastest, but the game will stutter while it runs. */
	EXTREME;

	public static final CpuLoad DEFAULT = BALANCED;

	/** Threads for this preset on a machine with this many logical CPUs. */
	public int threads(int logical) {
		logical = Math.max(1, logical);
		int physical = physicalCores(logical);
		int balanced = Math.max(1, Math.min(physical - 2, logical - 1));
		return switch (this) {
			case MINIMAL -> 1;
			case LOW -> Math.max(1, physical / 4);
			case BALANCED -> balanced;
			case HIGH -> Math.max(balanced, logical - Math.max(2, logical / 4));
			case EXTREME -> Math.max(1, logical - 1);
		};
	}

	public int threads() {
		return this.threads(Runtime.getRuntime().availableProcessors());
	}

	/**
	 * The preset's own, lower thread priority: what Minimal and Low always run at, and Balanced, High and Extreme with
	 * {@code threadPriority=lower} (all threads, as in 1.3.0) or while the server is behind (automatic). See
	 * {@link SectionScheduler#priorityFor}.
	 */
	public int priority() {
		return switch (this) {
			case MINIMAL -> Thread.MIN_PRIORITY;
			case LOW -> Thread.MIN_PRIORITY + 1;
			case BALANCED -> Thread.NORM_PRIORITY - 2;
			case HIGH -> Thread.NORM_PRIORITY - 1;
			case EXTREME -> Thread.NORM_PRIORITY;
		};
	}

	/** Logical CPUs that are full cores: with 8 or more, every second one is taken to be a hyper-thread. */
	static int physicalCores(int logical) {
		return logical >= 8 ? logical / 2 : logical;
	}

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public String label() {
		return switch (this) {
			case MINIMAL -> "Minimal";
			case LOW -> "Low";
			case BALANCED -> "Balanced";
			case HIGH -> "High";
			case EXTREME -> "Extreme";
		};
	}

	public static CpuLoad parse(String value, CpuLoad fallback) {
		if (value == null) return fallback;
		for (CpuLoad load : values()) {
			if (load.id().equalsIgnoreCase(value.trim())) return load;
		}
		return fallback;
	}
}
