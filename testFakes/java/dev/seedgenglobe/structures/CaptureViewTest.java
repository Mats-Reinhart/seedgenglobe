package dev.seedgenglobe.structures;

import java.util.Random;
import java.util.function.Predicate;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import dev.seedgenglobe.terrain.ColumnProfile;
import dev.seedgenglobe.terrain.SampledColumns;

/**
 * Checks the section structures are placed into: which block columns are a LOD section's sample points, what reads
 * see, which writes are kept, heightmaps over terrain plus placed blocks, and how placed blocks end up in the
 * columns. Runs on the same small fakes as ColumnProfileTest; see test-offline.sh.
 */
public final class CaptureViewTest {

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState STONE = Blocks.STONE.defaultBlockState();
	static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
	static final BlockState PLANKS = Blocks.OAK_LOG.defaultBlockState();
	static final BlockState WATER = Blocks.WATER.defaultBlockState();
	static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
	static final int MIN = -64, MAX = 320;

	static final Predicate<BlockState> NOT_AIR = state -> !state.isAir();
	static final Predicate<BlockState> OPAQUE = state -> state.getLightDampening() >= 15;

	static int checks, failures;

	/** Flat terrain: stone, then grass with its top at the given height. */
	static final class Columns implements SampledColumns {

		final ColumnProfile[] profiles = new ColumnProfile[WIDTH * WIDTH];

		Columns(int height) {
			for (int index = 0; index < this.profiles.length; index++) {
				ColumnProfile profile = new ColumnProfile();
				profile.clear();
				profile.add(AIR, height, MAX, 15, 0);
				profile.add(GRASS, height - 1, height, 15, 0);
				profile.add(STONE, MIN, height - 1, 0, 0);
				profile.findSurface(MIN);
				this.profiles[index] = profile;
			}
		}

		@Override
		public ColumnProfile profile(int relX, int relZ) {
			return this.profiles[relX * WIDTH + relZ];
		}

		@Override
		public BlockState air() {
			return AIR;
		}

		@Override
		public int halfCell(int detail) {
			return detail > 0 ? 1 << (detail - 1) : 0;
		}
	}

	public static void main(String[] args) {
		grid();
		gridFuzz(20_000);
		readsAndWrites();
		heights();
		applying();
		System.out.println("CaptureViewTest: " + checks + " checks, " + failures + " failures");
		if (failures > 0) System.exit(1);
	}

	static void grid() {
		SampleGrid grid = new SampleGrid();
		grid.set(128, -64, 0, 0);
		check("detail 0: every column is a sample", grid.isSample(128, -64) && grid.isSample(191, -1) && !grid.isSample(192, -1) && !grid.isSample(127, 0), "");
		check("detail 0: cells", grid.cellX(128) == 0 && grid.cellX(191) == 63 && grid.cellX(100) == 0 && grid.cellX(500) == 63, "");
		check("detail 0: sample positions", grid.sampleX(5) == 133 && grid.sampleZ(5) == -59, "");

		grid.set(-512, 0, 3, 4);
		check("detail 3: samples sit 4 blocks into their cell", grid.sampleX(0) == -508 && grid.isSample(-508, 4) && !grid.isSample(-507, 4) && !grid.isSample(-512, 4), "");
		check("detail 3: the cell of a block", grid.cellX(-512) == 0 && grid.cellX(-505) == 0 && grid.cellX(-504) == 1, "");
		check("detail 3: first and last cells", grid.firstCellX(-508) == 0 && grid.firstCellX(-507) == 1 && grid.lastCellX(-509) == -1 && grid.lastCellX(-508) == 0, "");
		check("detail 3: beyond the section", grid.firstCellX(10_000) == SampleGrid.WIDTH && grid.lastCellX(10_000) == SampleGrid.WIDTH - 1 && grid.firstCellX(-10_000) == 0, "");
	}

	/** The cells between firstCell and lastCell are exactly those whose sample point lies in the range. */
	static void gridFuzz(int iterations) {
		Random random = new Random(7L);
		SampleGrid grid = new SampleGrid();
		for (int i = 0; i < iterations; i++) {
			int detail = random.nextInt(7);
			int span = SampleGrid.WIDTH << detail;
			int originX = (random.nextInt(200) - 100) * span;
			int originZ = (random.nextInt(200) - 100) * span;
			int half = detail > 0 && random.nextBoolean() ? 1 << (detail - 1) : 0;
			grid.set(originX, originZ, detail, half);
			int a = originX - span / 2 + random.nextInt(span * 2);
			int b = a + random.nextInt(span);
			int first = grid.firstCellX(a), last = grid.lastCellX(b);
			boolean ok = true;
			for (int cell = 0; cell < SampleGrid.WIDTH; cell++) {
				int x = grid.sampleX(cell);
				boolean inRange = x >= a && x <= b;
				boolean listed = cell >= first && cell <= last;
				if (inRange != listed) ok = false;
				if (!grid.isSample(x, grid.sampleZ(cell))) ok = false;
				if (grid.cellX(x) != cell) ok = false;
			}
			check("grid fuzz " + i, ok, "detail " + detail + " origin " + originX + " half " + half + " range " + a + ".." + b + " -> " + first + ".." + last);
		}
	}

	static CaptureView view(int detail, int height) {
		CaptureView view = new CaptureView(new Columns(height));
		view.begin(0, 0, detail, MIN, MAX);
		return view;
	}

	static void readsAndWrites() {
		CaptureView view = view(2, 64); // samples at 2, 6, 10, ...
		check("terrain at a sample point", view.state(2, 63, 2) == GRASS && view.state(2, 62, 2) == STONE && view.state(2, 64, 2) == AIR, "");
		check("terrain anywhere in the cell is the cell's column", view.state(0, 63, 3) == GRASS, "");
		view.write(2, 70, 2, PLANKS);
		check("a block placed at a sample point is kept", view.state(2, 70, 2) == PLANKS && view.anyWrites(), "");
		check("and seen from the rest of its cell", view.state(1, 70, 0) == PLANKS, "");
		view.write(3, 71, 2, PLANKS);
		check("a block placed between sample points is dropped", view.state(2, 71, 2) == AIR, "");
		view.write(2, MAX, 2, PLANKS);
		view.write(2, MIN - 1, 2, PLANKS);
		view.write(2, 70, 2, null);
		check("blocks outside the level and null blocks are ignored", view.state(2, MAX, 2) == AIR && view.state(2, MIN - 1, 2) == AIR && view.state(2, 70, 2) == PLANKS, "");
		view.write(2, 70, 2, WATER);
		check("the last block placed wins", view.state(2, 70, 2) == WATER, "");
		view.write(1000, 70, 2, PLANKS);
		check("blocks outside the section are dropped", view.state(255, 70, 2) == AIR, "");
		view.end();
		check("end forgets placed blocks", !view.anyWrites() && view.state(2, 70, 2) == AIR, "");
	}

	static void heights() {
		CaptureView view = view(0, 64);
		check("plain terrain height", view.height(NOT_AIR, 5, 5) == 64 && view.height(OPAQUE, 5, 5) == 64, "");
		view.write(5, 70, 5, PLANKS);
		check("a block above the terrain raises the height", view.height(NOT_AIR, 5, 5) == 71, "");
		view.write(6, 63, 6, AIR);
		check("removing the top block lowers it", view.height(NOT_AIR, 6, 6) == 63 && view.height(OPAQUE, 6, 6) == 63, "");
		for (int y = 64; y <= 66; y++) view.write(7, y, 7, WATER);
		check("water counts as a block but not as opaque", view.height(NOT_AIR, 7, 7) == 67 && view.height(OPAQUE, 7, 7) == 64, "");
		view.write(8, 10, 8, AIR);
		check("blocks placed underground change nothing", view.height(NOT_AIR, 8, 8) == 64, "");
		view.write(9, 63, 9, AIR);
		view.write(9, 62, 9, AIR);
		view.write(9, 61, 9, LEAVES);
		check("dug out down to leaves", view.height(NOT_AIR, 9, 9) == 62 && view.height(OPAQUE, 9, 9) == 61, "");
		view.write(10, 63, 10, AIR);
		view.write(10, 50, 10, AIR);
		check("a removed top block over a cave placed lower down", view.height(NOT_AIR, 10, 10) == 63, "");
		view.end();
	}

	static void applying() {
		Columns columns = new Columns(64);
		CaptureView view = new CaptureView(columns);
		view.begin(0, 0, 0, MIN, MAX);
		// a little house at (3, 3), water at (4, 4), a deep air pocket at (5, 5)
		view.write(3, 63, 3, PLANKS);
		for (int y = 64; y < 68; y++) view.write(3, y, 3, AIR);
		view.write(3, 68, 3, PLANKS);
		view.write(4, 63, 4, WATER);
		view.write(5, 10, 5, AIR);
		boolean[] occupied = new boolean[CaptureView.COLUMNS];
		view.apply(15, occupied);
		view.end();
		ColumnProfile house = columns.profile(3, 3);
		check("the house is in its column", house.stateAt(68) == PLANKS && house.stateAt(65) == AIR && house.stateAt(63) == PLANKS && house.surfaceTop == 69, house);
		check("and relit: dark inside", house.skyLight[house.runAt(65)] == 0 && house.skyLight[house.runAt(68)] == 15, house);
		ColumnProfile pond = columns.profile(4, 4);
		check("water replaced the grass", pond.stateAt(63) == WATER && pond.surfaceTop == 64 && pond.skyLight[pond.runAt(62)] == 14, pond);
		check("structures cover the house and the pond", occupied[3 * CaptureView.WIDTH + 3] && occupied[4 * CaptureView.WIDTH + 4], "");
		check("but not the ground above an air pocket", !occupied[5 * CaptureView.WIDTH + 5] && columns.profile(5, 5).stateAt(10) == AIR, columns.profile(5, 5));
		check("other columns are untouched", !occupied[0] && columns.profile(0, 0).stateAt(63) == GRASS, "");
		// a level of another height gets fresh buffers
		view.begin(0, 0, 0, MIN, MAX + 64);
		view.write(1, MAX + 10, 1, PLANKS);
		check("taller level", view.state(1, MAX + 10, 1) == PLANKS, "");
		view.end();
	}

	static void check(String name, boolean ok, Object detail) {
		checks++;
		if (!ok) {
			failures++;
			if (failures <= 20) System.out.println("FAIL " + name + ": " + detail);
		}
	}
}
