package dev.seedgenglobe.gen;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * How full the heap is with live data, judged by what the garbage collector kept after its last collection (the heap
 * pools' "collection usage"), which unlike the raw heap usage does not count garbage waiting to be collected. Falls back
 * to the raw usage on a collector that does not report it. Updated once a second by the progress reporter.
 */
public final class MemoryWatch {

	public enum Pressure { NORMAL, HIGH, CRITICAL }

	static final double HIGH = 0.80D, CRITICAL = 0.92D, RELIEF = 0.05D;

	private static volatile Pressure pressure = Pressure.NORMAL;
	private static volatile double liveRatio;
	private static volatile boolean collectionUsage = true;

	private MemoryWatch() {}

	public static Pressure pressure() {
		return pressure;
	}

	/** Fraction of the maximum heap holding live data after the last collection (0 to 1). */
	public static double liveRatio() {
		return liveRatio;
	}

	static void update() {
		Runtime runtime = Runtime.getRuntime();
		long max = runtime.maxMemory();
		if (max <= 0L || max == Long.MAX_VALUE) return;
		long live = -1L;
		if (collectionUsage) {
			try {
				long sum = 0L;
				boolean any = false;
				List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
				for (MemoryPoolMXBean pool : pools) {
					if (pool.getType() != MemoryType.HEAP || !pool.isValid()) continue;
					MemoryUsage usage = pool.getCollectionUsage();
					if (usage == null) continue;
					sum += usage.getUsed();
					any = true;
				}
				if (any) live = sum;
				else collectionUsage = false;
			}
			catch (RuntimeException | LinkageError error) {
				collectionUsage = false;
			}
		}
		if (live < 0L) live = runtime.totalMemory() - runtime.freeMemory();
		double ratio = (double)(live) / max;
		liveRatio = ratio;
		Pressure current = pressure;
		Pressure next;
		if (ratio >= CRITICAL) next = Pressure.CRITICAL;
		else if (ratio >= HIGH) next = current == Pressure.CRITICAL && ratio > CRITICAL - RELIEF ? Pressure.CRITICAL : Pressure.HIGH;
		else if (current != Pressure.NORMAL && ratio > HIGH - RELIEF) next = Pressure.HIGH;
		else next = Pressure.NORMAL;
		pressure = next;
	}
}
