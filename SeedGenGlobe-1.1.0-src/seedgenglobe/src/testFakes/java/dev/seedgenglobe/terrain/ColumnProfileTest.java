package dev.seedgenglobe.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import builderb0y.bigglobe.chunkgen.scripted.BlockSegmentList;
import builderb0y.bigglobe.columns.ScriptedColumn;

/**
 * Checks {@link ColumnProfile} against the rules Distant Horizons enforces for API data points
 * (top-down, contiguous, no gaps, heights 1-6094) and against Big Globe's light model.
 * Runs on plain Java with small fakes of the Minecraft and Big Globe classes involved; see test-offline.sh.
 */
public final class ColumnProfileTest {

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState STONE = Blocks.STONE.defaultBlockState();
	static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
	static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
	static final BlockState SAND = Blocks.SAND.defaultBlockState();
	static final BlockState WATER = Blocks.WATER.defaultBlockState();
	static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
	static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
	static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
	static final BlockState GLOWSTONE = Blocks.GLOWSTONE.defaultBlockState();
	static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

	static final int MIN = -64, MAX = 320;
	static final ScriptedColumn COLUMN = new ScriptedColumn();

	static int checks, failures;

	public static void main(String[] args) {
		plainColumn();
		shallowOcean();
		scriptedSegment();
		gapUnderOverhang();
		floatingIsland();
		emptyColumn();
		segmentsOutsideLevel();
		emissiveBlocks();
		overlays();
		fuzz(20_000);
		System.out.println("ColumnProfileTest: " + checks + " checks, " + failures + " failures");
		if (failures > 0) System.exit(1);
	}

	// ------------------------------------------------------------------------------------------------- scenarios

	static void plainColumn() {
		BlockSegmentList list = new BlockSegmentList(MIN, MAX)
			.fixed(-64, -64, BEDROCK)
			.fixed(-63, 59, STONE)
			.fixed(60, 62, DIRT)
			.fixed(63, 63, GRASS)
			.fixed(64, 319, AIR);
		ColumnProfile p = build(list, MIN, MAX, 15);
		expectRuns("plain", p,
			run(AIR, 64, 320, 15),
			run(GRASS, 63, 64, 15),
			run(DIRT, 60, 63, 0),
			run(STONE, -63, 60, 0),
			run(BEDROCK, -64, -63, 0)
		);
		check("plain surface", p.surfaceTop == 64 && p.surfaceRun == 1, p);
	}

	static void shallowOcean() {
		BlockSegmentList list = new BlockSegmentList(MIN, MAX)
			.fixed(-64, 49, STONE)
			.fixed(50, 57, SAND)
			.fixed(58, 62, WATER)
			.fixed(63, 319, AIR);
		ColumnProfile p = build(list, MIN, MAX, 15);
		// 5 blocks of water dim the sky light that reaches the sea floor from 15 to 10, as in Big Globe's model
		expectRuns("ocean", p,
			run(AIR, 63, 320, 15),
			run(WATER, 58, 63, 15),
			run(SAND, 50, 58, 10),
			run(STONE, -64, 50, 0)
		);
		check("ocean surface is the water top", p.surfaceTop == 63 && p.states[p.surfaceRun] == WATER, p);
	}

	static void scriptedSegment() {
		BlockSegmentList list = new BlockSegmentList(MIN, MAX)
			.dynamic(-64, 63, y -> y == 63 ? GRASS : (y >= 60 ? DIRT : STONE))
			.fixed(64, 319, AIR);
		ColumnProfile p = build(list, MIN, MAX, 15);
		// one segment: every run carries the segment's light, exactly like Big Globe's own DH generator
		expectRuns("scripted", p,
			run(AIR, 64, 320, 15),
			run(GRASS, 63, 64, 15),
			run(DIRT, 60, 63, 15),
			run(STONE, -64, 60, 15)
		);
	}

	static void gapUnderOverhang() {
		// no root air fill: the space between the two stone segments and above them are gaps
		BlockSegmentList list = new BlockSegmentList(MIN, MAX)
			.fixed(-64, 10, STONE)
			.fixed(20, 30, STONE);
		ColumnProfile p = build(list, MIN, MAX, 15);
		expectRuns("gap", p,
			run(AIR, 31, 320, 15),
			run(STONE, 20, 31, 15),
			run(AIR, 11, 20, 0), // under 11 blocks of stone: dark, so DH's cave culling can hide it
			run(STONE, -64, 11, 0)
		);
	}

	static void floatingIsland() {
		BlockSegmentList list = new BlockSegmentList(MIN, MAX).fixed(100, 110, STONE);
		ColumnProfile p = build(list, MIN, MAX, 15);
		expectRuns("island", p,
			run(AIR, 111, 320, 15),
			run(STONE, 100, 111, 15),
			run(AIR, -64, 100, 0)
		);
		check("island surface", p.surfaceTop == 111, p);
	}

	static void emptyColumn() {
		ColumnProfile p = build(new BlockSegmentList(MIN, MAX), MIN, MAX, 15);
		expectRuns("empty", p, run(AIR, -64, 320, 15));
		check("empty surface", p.surfaceRun == -1 && p.surfaceTop == MIN, p);
		ColumnProfile dark = build(new BlockSegmentList(MIN, MAX), MIN, MAX, 0);
		expectRuns("empty, no sky", dark, run(AIR, -64, 320, 0));
		ColumnProfile air = new ColumnProfile();
		air.fillAir(AIR, MIN, MAX, (byte)(15));
		expectRuns("fillAir", air, run(AIR, -64, 320, 15));
	}

	static void segmentsOutsideLevel() {
		// the generator's range is larger than the level's: everything outside the level is cut off
		BlockSegmentList list = new BlockSegmentList(-200, 500)
			.fixed(-200, -100, BEDROCK)
			.fixed(-99, 70, STONE)
			.fixed(71, 400, AIR)
			.fixed(401, 499, STONE);
		ColumnProfile p = build(list, MIN, MAX, 15);
		// the stone above the level still shades everything below it, like it would in Big Globe
		expectRuns("clipped", p,
			run(AIR, 71, 320, 0),
			run(STONE, -64, 71, 0)
		);
		BlockSegmentList high = new BlockSegmentList(-200, 500)
			.fixed(-200, -100, BEDROCK)
			.fixed(-99, 70, STONE)
			.fixed(71, 499, AIR);
		ColumnProfile q = build(high, MIN, MAX, 15);
		expectRuns("clipped open", q,
			run(AIR, 71, 320, 15),
			run(STONE, -64, 71, 15)
		);
	}

	static void emissiveBlocks() {
		BlockSegmentList list = new BlockSegmentList(MIN, MAX)
			.fixed(-64, 40, STONE)
			.fixed(41, 41, GLOWSTONE)
			.fixed(42, 319, AIR);
		ColumnProfile p = build(list, MIN, MAX, 15);
		int run = p.runAt(41);
		check("glowstone run", run >= 0 && p.states[run] == GLOWSTONE && p.blockLight[run] == 15, p);
		check("stone has no block light", p.blockLight[p.runAt(0)] == 0, p);
	}

	static void overlays() {
		ColumnProfile p = plain();
		// a canopy above the grass, with snow on top, the way CanopyModel lays one
		p.overlay(LEAVES, 64, 70, 15, 0);
		p.overlay(SNOW, 70, 71, 15, 0);
		p.findSurface(p.bottoms[p.count - 1]);
		expectRuns("canopy", p,
			run(AIR, 71, 320, 15),
			run(SNOW, 70, 71, 15),
			run(LEAVES, 64, 70, 15),
			run(GRASS, 63, 64, 15),
			run(DIRT, 60, 63, 0),
			run(STONE, -63, 60, 0),
			run(BEDROCK, -64, -63, 0)
		);
		check("canopy surface", p.surfaceTop == 71 && p.states[p.surfaceRun] == SNOW, p);

		ColumnProfile across = plain();
		across.overlay(LOG, 60, 66, 15, 0); // replaces dirt and grass completely, and air partly
		expectRuns("across runs", across,
			run(AIR, 66, 320, 15),
			run(LOG, 60, 66, 15),
			run(STONE, -63, 60, 0),
			run(BEDROCK, -64, -63, 0)
		);

		ColumnProfile clipped = plain();
		clipped.overlay(LEAVES, 318, 330, 15, 0);
		clipped.overlay(LEAVES, -80, -63, 15, 0);
		expectRuns("overlay clipped to the level", clipped,
			run(LEAVES, 318, 320, 15),
			run(AIR, 64, 318, 15),
			run(GRASS, 63, 64, 15),
			run(DIRT, 60, 63, 0),
			run(STONE, -63, 60, 0),
			run(LEAVES, -64, -63, 15)
		);

		ColumnProfile same = plain();
		int before = same.count;
		same.overlay(AIR, 100, 120, 15, 0); // identical to what is there: must merge back into one run
		check("identical overlay merges", same.count == before, same);
		valid("identical overlay", same, MIN, MAX);

		ColumnProfile exact = plain();
		exact.overlay(SAND, 60, 63, 0, 0);
		expectRuns("exact run", exact,
			run(AIR, 64, 320, 15),
			run(GRASS, 63, 64, 15),
			run(SAND, 60, 63, 0),
			run(STONE, -63, 60, 0),
			run(BEDROCK, -64, -63, 0)
		);

		ColumnProfile empty = plain();
		empty.overlay(LEAVES, 70, 70, 15, 0);
		empty.overlay(LEAVES, 90, 80, 15, 0);
		check("empty overlays change nothing", empty.count == 5, empty);
	}

	/** Random columns, random overlays: the profile must always be something Distant Horizons accepts. */
	static void fuzz(int iterations) {
		Random random = new Random(26_1_2L);
		BlockState[] palette = { AIR, STONE, DIRT, GRASS, SAND, WATER, LEAVES, GLOWSTONE, SNOW };
		ColumnProfile profile = new ColumnProfile(); // reused, like the samplers reuse theirs
		for (int i = 0; i < iterations; i++) {
			int levelMin = (random.nextInt(9) - 4) * 64;
			int levelMax = levelMin + 64 + random.nextInt(4000);
			int genMin = levelMin - random.nextInt(3) * 32;
			int genMax = levelMax + random.nextInt(3) * 32;
			BlockSegmentList list = new BlockSegmentList(genMin, genMax);
			int y = genMin;
			while (y < genMax) {
				int length = 1 + random.nextInt(random.nextBoolean() ? 8 : 300);
				int top = Math.min(genMax - 1, y + length - 1);
				if (random.nextInt(4) != 0) {
					if (random.nextInt(3) == 0) {
						long seed = random.nextLong();
						list.dynamic(y, top, yy -> palette[(int)(Math.floorMod(seed ^ (yy / 3), 4L)) + 1]);
					}
					else {
						list.fixed(y, top, palette[random.nextInt(palette.length)]);
					}
				}
				y = top + 1 + (random.nextInt(3) == 0 ? random.nextInt(20) : 0); // sometimes leave a gap
			}
			profile.build(COLUMN, list, levelMin, levelMax, (byte)(random.nextBoolean() ? 15 : 0), AIR);
			if (!valid("fuzz " + i + " build", profile, levelMin, levelMax)) continue;
			int overlays = random.nextInt(4);
			for (int o = 0; o < overlays; o++) {
				int bottom = levelMin - 10 + random.nextInt(levelMax - levelMin + 20);
				int top = bottom + random.nextInt(30) - 2;
				profile.overlay(palette[random.nextInt(palette.length)], bottom, top, random.nextInt(16), 0);
				profile.findSurface(profile.bottoms[profile.count - 1]);
				if (!valid("fuzz " + i + " overlay " + o, profile, levelMin, levelMax)) break;
			}
		}
	}

	// --------------------------------------------------------------------------------------------- helpers

	static ColumnProfile plain() {
		BlockSegmentList list = new BlockSegmentList(MIN, MAX)
			.fixed(-64, -64, BEDROCK)
			.fixed(-63, 59, STONE)
			.fixed(60, 62, DIRT)
			.fixed(63, 63, GRASS)
			.fixed(64, 319, AIR);
		return build(list, MIN, MAX, 15);
	}

	static ColumnProfile build(BlockSegmentList list, int min, int max, int sky) {
		ColumnProfile profile = new ColumnProfile();
		profile.build(COLUMN, list, min, max, (byte)(sky), AIR);
		valid("build", profile, min, max);
		return profile;
	}

	record Run(BlockState state, int bottom, int top, int sky) {}

	static Run run(BlockState state, int bottom, int top, int sky) {
		return new Run(state, bottom, top, sky);
	}

	static void expectRuns(String name, ColumnProfile profile, Run... expected) {
		List<Run> actual = new ArrayList<>();
		for (int i = 0; i < profile.count; i++) {
			actual.add(new Run(profile.states[i], profile.bottoms[i], profile.tops[i], profile.skyLight[i]));
		}
		check(name, actual.equals(List.of(expected)), "expected " + List.of(expected) + "\n   but got  " + actual);
		valid(name, profile, profile.count > 0 ? profile.bottoms[profile.count - 1] : MIN, profile.count > 0 ? profile.tops[0] : MAX);
	}

	/**
	 * The rules of Distant Horizons' LodDataBuilder.validateOrThrowApiDataColumn and convertApiDataPointListToPackedLongArray,
	 * applied to the data points DhWriter would create from this profile, plus SeedGenGlobe's own invariants.
	 */
	static boolean valid(String name, ColumnProfile p, int levelMin, int levelMax) {
		String problem = null;
		if (p.count <= 0) problem = "no runs";
		int lastBottom = Integer.MIN_VALUE;
		for (int i = 0; problem == null && i < p.count; i++) {
			int bottom = p.bottoms[i] - levelMin, top = p.tops[i] - levelMin; // as DhWriter writes them
			int height = top - bottom;
			if (p.states[i] == null) problem = "null state at " + i;
			else if (bottom > top) problem = "upside down at " + i;
			else if (height <= 0 || height >= 6095) problem = "bad height " + height + " at " + i;
			else if (lastBottom != Integer.MIN_VALUE && lastBottom > top) problem = "overlap at " + i;
			else if (lastBottom != Integer.MIN_VALUE && top != lastBottom) problem = "gap at " + i;
			else if (p.skyLight[i] < 0 || p.skyLight[i] > 15 || p.blockLight[i] < 0 || p.blockLight[i] > 15) problem = "light out of range at " + i;
			else if (i > 0 && p.states[i] == p.states[i - 1] && p.skyLight[i] == p.skyLight[i - 1] && p.blockLight[i] == p.blockLight[i - 1]) problem = "unmerged runs at " + i;
			lastBottom = bottom;
		}
		if (problem == null && p.tops[0] != levelMax) problem = "top " + p.tops[0] + " is not the level top " + levelMax;
		if (problem == null && p.bottoms[p.count - 1] != levelMin) problem = "bottom " + p.bottoms[p.count - 1] + " is not the level bottom " + levelMin;
		if (problem == null) {
			int surface = -1;
			for (int i = 0; i < p.count; i++) {
				if (!p.states[i].isAir()) {
					surface = i;
					break;
				}
			}
			if (surface != p.surfaceRun || (surface >= 0 ? p.tops[surface] : levelMin) != p.surfaceTop) problem = "stale surface " + p.surfaceRun + "/" + p.surfaceTop;
		}
		return check(name + " is valid for Distant Horizons", problem == null, problem + " in " + p);
	}

	static boolean check(String name, boolean ok, Object detail) {
		checks++;
		if (!ok) {
			failures++;
			if (failures <= 20) System.out.println("FAIL " + name + ": " + detail);
		}
		return ok;
	}
}
