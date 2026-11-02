package dev.seedgenglobe.gen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the players are, per dimension, so the scheduler can work nearest first. Updated from the server tick (every
 * player of every level) and from the client tick (the local player, for client generation and the Xaero LOD map).
 * Positions are block x/z pairs; readers on any thread get a consistent snapshot.
 */
public final class Focus {

	/** One dimension's player positions: x0, z0, x1, z1, ... */
	record Points(int[] xz, long at) {}

	private static final Map<String, Points> BY_DIMENSION = new ConcurrentHashMap<>();
	/** Positions older than this are ignored (the player left, the level unloaded). */
	static final long STALE_NANOS = 30_000_000_000L;

	private Focus() {}

	public static void set(String dimension, int[] xz) {
		if (xz.length == 0) {
			BY_DIMENSION.remove(dimension);
			return;
		}
		BY_DIMENSION.put(dimension, new Points(xz.clone(), System.nanoTime()));
	}

	/**
	 * Sets the positions unless fresher ones came in the last two seconds (from the server, which knows every player).
	 * Keeps the order nearest-first while a singleplayer game is paused and its server does not tick.
	 */
	public static void refresh(String dimension, int[] xz) {
		Points points = BY_DIMENSION.get(dimension);
		if (points != null && System.nanoTime() - points.at() < 2_000_000_000L) return;
		set(dimension, xz);
	}

	public static void clear(String dimension) {
		BY_DIMENSION.remove(dimension);
	}

	public static void clearAll() {
		BY_DIMENSION.clear();
	}

	/** The positions known for a dimension (x, z pairs), empty when none are known or they are stale. */
	public static int[] points(String dimension) {
		Points points = BY_DIMENSION.get(dimension);
		if (points == null || System.nanoTime() - points.at() > STALE_NANOS) return new int[0];
		return points.xz();
	}

	/**
	 * Distance in blocks from the nearest player to a square area (0 when a player stands inside it),
	 * or {@link Double#POSITIVE_INFINITY} when no player of that dimension is known.
	 */
	public static double distance(String dimension, long minX, long minZ, long span) {
		return distance(points(dimension), minX, minZ, span);
	}

	static double distance(int[] xz, long minX, long minZ, long span) {
		double best = Double.POSITIVE_INFINITY;
		for (int i = 0; i + 1 < xz.length; i += 2) {
			double d = distanceToSquare(xz[i], xz[i + 1], minX, minZ, span);
			if (d < best) best = d;
		}
		return best;
	}

	public static double distanceToSquare(long x, long z, long minX, long minZ, long span) {
		double dx = x < minX ? minX - x : Math.max(0L, x - (minX + span - 1));
		double dz = z < minZ ? minZ - z : Math.max(0L, z - (minZ + span - 1));
		return Math.sqrt(dx * dx + dz * dz);
	}
}
