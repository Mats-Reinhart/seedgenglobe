package dev.seedgenglobe.terrain;

import java.util.Random;

/**
 * Checks the ground cover model's pure parts against the rules they reproduce:
 * <ul>
 * <li>{@link GroundCover#mostCommon} against a literal transcription of Distant Horizons'
 * {@code FullDataSourceV2.determineMostCommonValueInColumnSlice} (with air = 0), for every input combination;</li>
 * <li>{@link GroundCover#smallDecoration} frequencies against the probabilities written in Big Globe's
 * surface_small_foliage, cherry_blossoms and surface_materials scripts, for a forest, a plain, a desert,
 * mycelium and mud;</li>
 * <li>the 2 x 2 merge of independently decided blocks, against the closed form for coverage.</li>
 * </ul>
 * Runs on the compiled mod classes; no Minecraft code is executed.
 */
public final class GroundCoverTest {

	static int checks, failures;

	public static void main(String[] args) {
		testMostCommon();
		testForest();
		testPlains();
		testDesert();
		testMyceliumAndMud();
		testMergedCoverage();
		System.out.println("GroundCoverTest: " + checks + " checks, " + failures + " failures");
		if (failures != 0) System.exit(1);
	}

	/** Distant Horizons' code, transcribed with "is air" meaning value 0. */
	static int dhMostCommon(int[] slice) {
		int value0 = slice[0], count0 = 0;
		int value1 = slice[1], count1 = 0;
		int value2 = slice[2], count2 = 0;
		int value3 = slice[3], count3 = 0;
		for (int i = 0; i < 4; i++) {
			int value = slice[i];
			if (value != 0) {
				if (value == value0) count0++;
				else if (value == value1) count1++;
				else if (value == value2) count2++;
				else count3++;
			}
		}
		int maxCount = Math.max(count0, Math.max(count1, Math.max(count2, count3)));
		if (maxCount == count0) return value0;
		if (maxCount == count1) return value1;
		return maxCount == count2 ? value2 : value3;
	}

	static void testMostCommon() {
		for (int a = 0; a < 5; a++) for (int b = 0; b < 5; b++) for (int c = 0; c < 5; c++) for (int d = 0; d < 5; d++) {
			int expected = dhMostCommon(new int[] { a, b, c, d });
			check("mostCommon " + a + b + c + d, GroundCover.mostCommon(a, b, c, d) == expected, expected);
		}
	}

	static GroundCover.Codes codes() {
		GroundCover.Codes k = new GroundCover.Codes();
		k.shortGrass = 1; k.fern = 2; k.tallGrass = 3; k.largeFern = 4; k.bigGlobeShortGrass = 5; k.deadBush = 6;
		k.shortDryGrass = 7; k.tallDryGrass = 8; k.pinkPetals = 9; k.leafLitter = 10; k.stick = 11; k.rock = 12;
		k.mushroomSpores = 13; k.charredGrass = 14;
		// grass-like plants and leaf litter can be replaced, petals and wildflowers (20) cannot
		k.replaceable = index -> index == 0 || (index >= 1 && index <= 8) || index == 10 || index == 13 || index == 14;
		return k;
	}

	static GroundCover.Context soil(double foliage, double temperature, double humidity, double magicalness, double snowChance) {
		GroundCover.Context c = new GroundCover.Context();
		c.ground = GroundCover.SOIL;
		c.grassy = true;
		c.deadBushesAllowed = true;
		double adjusted = foliage / Math.sqrt(foliage * foliage + 1.0D);
		c.adjustedFoliage = adjusted * 0.5D + 0.5D;
		double fromTemperate = temperature * temperature + foliage * foliage;
		c.deadBushChance = fromTemperate / (fromTemperate * 64.0D + 4.0D);
		c.fernChance = humidity * 0.125D + 0.125D;
		double hot = smoothUnmix(0.75D, 0.5D, temperature), temperate = smoothUnmix(0.125D, -0.125D, temperature);
		c.cold = Math.max(0.0D, Math.min(1.0D, 1.0D - snowChance));
		double forest = foliage > 0.0D ? foliage / (foliage + 0.25D) : 0.0D;
		double plains = Math.max(-foliage * (foliage + 1.0D), 0.0D);
		double petal = magicalness - 0.5D > 0.0D ? (magicalness - 0.5D) / (magicalness - 0.5D + 1.0D) : 0.0D;
		double leaf = 1.0D - magicalness > 0.0D ? (1.0D - magicalness) / (2.0D - magicalness) : 0.0D;
		c.petalWeight = forest * petal * temperate;
		c.litterWeight = forest * leaf * hot;
		c.wildWeight = plains * hot;
		c.wildKind = 20;
		c.cherryTotal = c.petalWeight + c.litterWeight + c.wildWeight;
		c.stickChance = Math.max(foliage, 0.0D) * 16.0D / 256.0D;
		c.rockChance = 0.0D;
		return c;
	}

	static double smoothUnmix(double from, double to, double value) {
		double t = Math.max(0.0D, Math.min(1.0D, (value - from) / (to - from)));
		return t * t * (3.0D - 2.0D * t);
	}

	static double[] frequencies(GroundCover.Context c, int n) {
		GroundCover.Codes k = codes();
		GroundCover.Draws draws = new GroundCover.Draws();
		double[] counts = new double[32];
		for (int i = 0; i < n; i++) {
			draws.start(0xC0FFEEL, i % 4096, i / 4096);
			counts[GroundCover.smallDecoration(draws, c, k)]++;
		}
		for (int i = 0; i < counts.length; i++) counts[i] /= n;
		return counts;
	}

	/** Probabilities of Big Globe's surface_small_foliage alone on soil. */
	static double[] smallFoliage(GroundCover.Context c) {
		double a = c.adjustedFoliage, db = c.deadBushChance, fern = c.fernChance;
		double[] p = new double[32];
		p[0] = 1.0D - a;
		p[6] = a * db;
		p[4] = a * (1.0D - db) * a * a * fern;
		p[3] = a * (1.0D - db) * a * a * (1.0D - fern);
		p[2] = a * (1.0D - db) * a * (1.0D - a) * fern;
		p[1] = a * (1.0D - db) * a * (1.0D - a) * (1.0D - fern);
		p[5] = a * (1.0D - db) * (1.0D - a);
		return p;
	}

	static void testForest() {
		// temperate, non-magical forest: leaf litter replaces a large share of the grass, sticks are common
		GroundCover.Context c = soil(1.0D, 0.0D, 0.0D, 0.0D, 0.0D);
		double[] f = frequencies(c, 2_000_000);
		double litter = c.litterWeight / c.cherryTotal * c.litterWeight * c.cold;
		double stick = c.stickChance;
		double[] p = smallFoliage(c);
		// after cherry_blossoms: every outcome is replaced by litter with chance `litter`
		// after surface_materials: replaceable outcomes (all small foliage and litter) become sticks with chance `stick`
		double expectedLitter = litter * (1.0D - stick);
		double expectedStick = stick;
		double expectedTall = p[3] * (1.0D - litter) * (1.0D - stick);
		double expectedNothing = p[0] * (1.0D - litter) * (1.0D - stick);
		near("forest leaf litter", f[10], expectedLitter, 0.003D);
		near("forest sticks", f[11], expectedStick, 0.003D);
		near("forest tall grass", f[3], expectedTall, 0.003D);
		near("forest bare ground", f[0], expectedNothing, 0.003D);
		near("forest total", sum(f), 1.0D, 1.0e-9D);
		check("forest has no petals or wildflowers", f[9] == 0.0D && f[20] == 0.0D, f[9] + " " + f[20]);
		// leaf litter around 40 % is what makes explored forests look brown in the distance
		check("forest litter share", expectedLitter > 0.3D && expectedLitter < 0.45D, expectedLitter);
	}

	static void testPlains() {
		// plains (negative foliage): Big Globe's short grass dominates, wildflowers appear, no litter or sticks
		GroundCover.Context c = soil(-0.3D, 0.2D, 0.5D, 0.0D, 0.0D);
		double[] f = frequencies(c, 2_000_000);
		double[] p = smallFoliage(c);
		double wild = c.wildWeight * c.cold; // the only cherry option with weight
		near("plains wildflowers", f[20], wild, 0.003D);
		near("plains Big Globe short grass", f[5], p[5] * (1.0D - wild), 0.003D);
		near("plains short grass", f[1], p[1] * (1.0D - wild), 0.003D);
		near("plains ferns", f[2], p[2] * (1.0D - wild), 0.002D);
		near("plains dead bushes", f[6], p[6] * (1.0D - wild), 0.002D);
		check("plains no sticks", f[11] == 0.0D, f[11]);
		check("plains no litter", f[10] == 0.0D, f[10]);
	}

	static void testDesert() {
		GroundCover.Context c = new GroundCover.Context();
		c.ground = GroundCover.SAND;
		c.hot = true;
		c.deadBushesAllowed = true;
		c.adjustedFoliage = 0.3D;
		c.deadBushChance = 0.05D;
		c.fernChance = 0.2D;
		double[] f = frequencies(c, 2_000_000);
		double a = 0.3D;
		near("desert dead bushes", f[6], a * 0.05D, 0.002D);
		near("desert tall dry grass", f[8], a * 0.95D * a * a * 0.015625D, 0.0005D);
		near("desert short dry grass", f[7], a * 0.95D * a * (1.0D - a) * 0.015625D, 0.0005D);
		check("desert grows no grass", f[1] == 0.0D && f[3] == 0.0D && f[5] == 0.0D, f[1] + " " + f[3] + " " + f[5]);
	}

	static void testMyceliumAndMud() {
		GroundCover.Context c = new GroundCover.Context();
		c.ground = GroundCover.MYCELIUM;
		c.mushrooms = true;
		c.adjustedFoliage = 0.8D;
		double[] f = frequencies(c, 2_000_000);
		near("mycelium spores", f[13], 0.8D * 0.8D * 0.0625D, 0.001D);
		c.ground = GroundCover.MUD;
		f = frequencies(c, 200_000);
		check("mud grows nothing", f[0] == 1.0D, f[0]);
	}

	/** Coverage of a 2 x 2 merge of blocks that each carry a decoration with chance p is 1 - (1 - p)^4. */
	static void testMergedCoverage() {
		Random random = new Random(42L);
		for (double p : new double[] { 0.1D, 0.35D, 0.5D, 0.85D }) {
			int covered = 0, n = 400_000;
			for (int i = 0; i < n; i++) {
				int[] v = new int[4];
				for (int j = 0; j < 4; j++) v[j] = random.nextDouble() < p ? 1 + random.nextInt(3) : 0;
				if (GroundCover.mostCommon(v[0], v[1], v[2], v[3]) != 0) covered++;
			}
			near("merged coverage p=" + p, covered / (double)(n), 1.0D - Math.pow(1.0D - p, 4.0D), 0.004D);
		}
	}

	static double sum(double[] values) {
		double total = 0.0D;
		for (double value : values) total += value;
		return total;
	}

	static void near(String name, double actual, double expected, double tolerance) {
		check(name, Math.abs(actual - expected) <= tolerance, "actual " + actual + ", expected " + expected);
	}

	static void check(String name, boolean ok, Object detail) {
		checks++;
		if (!ok) {
			failures++;
			System.out.println("FAILED " + name + ": " + detail);
		}
	}
}
