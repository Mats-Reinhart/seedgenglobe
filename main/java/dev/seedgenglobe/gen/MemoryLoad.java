package dev.seedgenglobe.gen;

import java.util.Locale;

/**
 * How much memory SeedGenGlobe may keep for speed: how many sections wait in its queue, how many structure tiles it
 * remembers, how much of the Xaero LOD map it holds in memory, and how far Distant Horizons' own queues may grow
 * before SeedGenGlobe stops asking for more. {@link #AUTO} picks one from the heap size Java was given.
 */
public enum MemoryLoad {

	AUTO,
	/** For 2 to 4 GB heaps: small caches, short queues. */
	LOW,
	/** For 4 to 8 GB heaps. */
	MEDIUM,
	/** For 8 GB and more. */
	HIGH;

	public static final MemoryLoad DEFAULT = AUTO;

	/** The preset AUTO stands for with this maximum heap (bytes). */
	public static MemoryLoad forHeap(long maxHeap) {
		long gigabytes = maxHeap >> 30;
		if (maxHeap <= 0L || maxHeap == Long.MAX_VALUE) return MEDIUM;
		if (gigabytes < 4) return LOW;
		if (gigabytes < 8) return MEDIUM;
		return HIGH;
	}

	/** This preset, with AUTO resolved for the running JVM. */
	public MemoryLoad resolve() {
		return this == AUTO ? forHeap(Runtime.getRuntime().maxMemory()) : this;
	}

	/** Structure tiles (512 x 512 blocks, one structure set each) remembered between sections. */
	public int structureTiles() {
		return switch (this.resolve()) {
			case LOW -> 4_096;
			case HIGH -> 49_152;
			default -> 16_384;
		};
	}

	/** Sections that may wait for a thread in addition to the running ones, per thread. */
	public int queuePerThread() {
		return switch (this.resolve()) {
			case LOW -> 1;
			case HIGH -> 3;
			default -> 2;
		};
	}

	/** Cells (about 16 bytes each) of LOD map regions being merged, and of regions the store keeps for the map writer. */
	public long mapCells() {
		return switch (this.resolve()) {
			case LOW -> 1L << 19;
			case HIGH -> 1L << 22;
			default -> 1L << 21;
		};
	}

	/** Xaero map regions (10 to 20 MB each) SeedGenGlobe may have Xaero load just to fill them. */
	public int xaeroForcedRegions() {
		return switch (this.resolve()) {
			case LOW -> 2;
			case HIGH -> 10;
			default -> 6;
		};
	}

	/**
	 * How many tasks Distant Horizons' render loader and file queue may each hold, per Distant Horizons thread, while
	 * it still asks for new sections in dimensions SeedGenGlobe generates ({@code dhRequestBoost}). Distant Horizons
	 * itself stops at 5.
	 */
	public int dhQueuePerThread() {
		return switch (this.resolve()) {
			case LOW -> 8;
			case HIGH -> 20;
			default -> 12;
		};
	}

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public String label() {
		return switch (this) {
			case AUTO -> "Automatic";
			case LOW -> "Low";
			case MEDIUM -> "Medium";
			case HIGH -> "High";
		};
	}

	public static MemoryLoad parse(String value, MemoryLoad fallback) {
		if (value == null) return fallback;
		for (MemoryLoad load : values()) {
			if (load.id().equalsIgnoreCase(value.trim())) return load;
		}
		return fallback;
	}
}
