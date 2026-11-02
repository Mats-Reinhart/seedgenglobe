package dev.seedgenglobe.terrain;

/**
 * Small 2D value noise with the same layout as Big Globe's {@code wildflowers_1/2} noise sources
 * (smooth layers of scale 32, 16 and 8 with amplitudes 0.5, 0.25 and 0.125), used where Big Globe's own noise
 * sources are not reachable. Values lie in [-0.875, 0.875].
 */
final class SmoothNoise {

	private SmoothNoise() {}

	static double sample(long seed, int x, int z) {
		return layer(seed, x, z, 32) * 0.5D + layer(seed + 1L, x, z, 16) * 0.25D + layer(seed + 2L, x, z, 8) * 0.125D;
	}

	static double layer(long seed, int x, int z, int scale) {
		int cellX = Math.floorDiv(x, scale), cellZ = Math.floorDiv(z, scale);
		double fx = smooth((x - cellX * scale) / (double)(scale));
		double fz = smooth((z - cellZ * scale) / (double)(scale));
		double v00 = corner(seed, cellX, cellZ), v10 = corner(seed, cellX + 1, cellZ);
		double v01 = corner(seed, cellX, cellZ + 1), v11 = corner(seed, cellX + 1, cellZ + 1);
		double top = v00 + (v10 - v00) * fx;
		double bottom = v01 + (v11 - v01) * fx;
		return top + (bottom - top) * fz;
	}

	static double corner(long seed, int x, int z) {
		return ((CanopyModel.mix(seed, x, z) >>> 11) * 0x1.0p-53D) * 2.0D - 1.0D;
	}

	static double smooth(double t) {
		return t * t * (3.0D - 2.0D * t);
	}
}
