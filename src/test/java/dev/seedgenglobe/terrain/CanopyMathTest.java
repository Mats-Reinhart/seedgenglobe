package dev.seedgenglobe.terrain;

/**
 * Sanity checks for the tree shape functions of {@link CanopyModel}, run against the compiled mod classes.
 * Only static helpers are called, so no Minecraft or Big Globe code runs.
 */
public final class CanopyMathTest {

	static int checks, failures;

	public static void main(String[] args) {
		byte[] kinds = { CanopyModel.SPLIT, CanopyModel.CONE, CanopyModel.NARROW, CanopyModel.TALL, CanopyModel.FLAT };
		double[][] heights = { { 6, 4 }, { 12, 12 }, { 12, 6 }, { 16, 8 }, { 8, 4 }, { 4, 4 } };
		for (byte kind : kinds) {
			for (double[] base : heights) {
				CanopyModel.PaletteInfo palette = new CanopyModel.PaletteInfo(kind, base[0], base[1], null, null);
				for (double foliage : new double[] { 0.01, 0.5, 1.0, 2.0, 8.0 }) {
					for (double u : new double[] { 1.0E-9, 0.001, 0.25, 0.5, 0.999999 }) {
						double h = CanopyModel.treeHeight(palette, foliage, u);
						check("height in range", h >= base[0] - 1.0E-9 && h <= 32.0, kind + " " + h);
						double r = CanopyModel.crownRadius(kind, h);
						check("radius in range", r > 1.0 && r <= 12.0, kind + " h=" + h + " r=" + r);
						double centreTop = CanopyModel.crownTop(kind, h, r, 0.0);
						double centreBottom = CanopyModel.crownBottom(kind, h, r, 0.0);
						check("centre canopy is thick", centreTop - centreBottom >= 2.0, kind + " h=" + h + ": " + centreBottom + ".." + centreTop);
						check("canopy sits above ground and near the tree top", centreBottom >= 1.0 && centreTop <= h + 2.0 + 1.0E-9, kind + " h=" + h);
						double previousTop = Double.POSITIVE_INFINITY, previousBottom = Double.NEGATIVE_INFINITY;
						for (int step = 0; step <= 20; step++) {
							double d = r * step / 20.0;
							double top = CanopyModel.crownTop(kind, h, r, d), bottom = CanopyModel.crownBottom(kind, h, r, d);
							check("crown narrows outwards", top <= previousTop + 1.0E-9 && bottom >= previousBottom - 1.0E-9, kind + " d=" + d);
							previousTop = top;
							previousBottom = bottom;
						}
					}
				}
			}
		}
		// the hash behind tree placement: deterministic and evenly spread
		double sum = 0.0;
		int n = 0, hits = 0;
		for (int x = -200; x < 200; x++) {
			for (int z = -200; z < 200; z++) {
				long hash = CanopyModel.mix(12345L, x, z);
				check("deterministic", hash == CanopyModel.mix(12345L, x, z), x + "," + z);
				double u = CanopyModel.unit(hash >>> 40);
				check("unit range", u >= 0.0 && u < 1.0, u);
				sum += u;
				if (u < CanopyModel.SUCCESS) hits++;
				n++;
			}
		}
		check("uniform mean", Math.abs(sum / n - 0.5) < 0.01, sum / n);
		check("tree attempt success rate", Math.abs(hits / (double)(n) - CanopyModel.SUCCESS) < 0.01, hits / (double)(n));
		for (double r = 1.0; r <= 12.0; r += 0.5) {
			double coverage = 1.0D - Math.exp(-CanopyModel.SUCCESS / (CanopyModel.GRID * CanopyModel.GRID) * Math.PI * r * r);
			check("coverage is a probability", coverage > 0.0 && coverage < 1.0, r);
		}
		System.out.println("CanopyMathTest: " + checks + " checks, " + failures + " failures");
		if (failures > 0) System.exit(1);
	}

	static void check(String name, boolean ok, Object detail) {
		checks++;
		if (!ok) {
			failures++;
			if (failures <= 20) System.out.println("FAIL " + name + ": " + detail);
		}
	}
}
