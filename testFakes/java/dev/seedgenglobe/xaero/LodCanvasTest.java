package dev.seedgenglobe.xaero;

import java.util.Random;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Checks how LOD sections are put together into map regions ({@link RegionCanvas}) and what the map writer and the
 * map builder read back from them ({@link LodRegion}):
 * <ul>
 * <li>a cell keeps the data of the finest section that covered it; coarser sections only fill empty or coarser
 * cells, equal ones replace, and empty pixels never overwrite;</li>
 * <li>a finer section splits the region's cells first, without changing what any block shows;</li>
 * <li>sections far larger than a region, sections that only touch it, and negative coordinates;</li>
 * <li>snapshots are independent copies with increasing revisions; a canvas continued from one goes on the same way;</li>
 * <li>the per-tile detail, the 8 x 8 coverage summary and the coarsest source match a block-by-block model.</li>
 * </ul>
 * The model keeps, for every block of the region, the finest section that covered it, and a 2,000-merge fuzz with
 * sections of detail 0 to 11 in random order compares every block. Runs on the same fakes as XaeroPixelTest.
 */
public final class LodCanvasTest {

	static final BlockState[] STATES = new BlockState[8];
	static {
		STATES[0] = Blocks.STONE.defaultBlockState();
		STATES[1] = Blocks.GRASS_BLOCK.defaultBlockState();
		STATES[2] = Blocks.SAND.defaultBlockState();
		STATES[3] = Blocks.SNOW.defaultBlockState();
		STATES[4] = Blocks.DIRT.defaultBlockState();
		STATES[5] = Blocks.OAK_LEAVES.defaultBlockState();
		STATES[6] = new Block("gravel", false, false, 0, 15).defaultBlockState();
		STATES[7] = new Block("podzol", false, false, 0, 15).defaultBlockState();
	}
	static final BlockState WATER = Blocks.WATER.defaultBlockState();
	static final ResourceKey<Biome> PLAINS = new ResourceKey<>("plains"), OCEAN = new ResourceKey<>("ocean");
	static final Object OWNER = new Object();

	static int checks, failures;

	public static void main(String[] args) {
		basics();
		coarseOverFine();
		finerSplits();
		huge();
		negative();
		snapshots();
		equalDetail();
		fuzz(new Random(11L), 120);
		System.out.println("LodCanvasTest: " + checks + " checks, " + failures + " failures");
		if (failures > 0) System.exit(1);
	}

	/** A section whose every pixel shows the given block at the given height (null: no data). */
	static MapSection section(int detail, int originX, int originZ, BlockState state, int height) {
		MapSection section = new MapSection(OWNER, detail, originX, originZ, true);
		for (int i = 0; i < MapSection.SIZE; i++) {
			section.state[i] = state;
			section.height[i] = height;
			section.topHeight[i] = height;
			section.biome[i] = PLAINS;
		}
		return section;
	}

	static void basics() {
		RegionCanvas canvas = RegionCanvas.empty(0, 0, 2);
		check("new canvas is empty", canvas.maxSource() == LodRegion.NO_SOURCE && !canvas.dirty, canvas.maxSource());
		int changed = canvas.merge(section(2, 0, 0, STATES[0], 70), 1000L);
		check("a 256 block section at detail 2 fills 64 x 64 cells", changed == 64 * 64, changed);
		check("the canvas is dirty since then", canvas.dirty && canvas.dirtySince == 1000L && canvas.changedAt == 1000L, "");
		check("cells outside the section stay empty", canvas.maxSource() == LodRegion.NO_SOURCE, canvas.maxSource());
		LodRegion region = canvas.snapshot();
		check("snapshot: revision 1, no longer dirty", region.revision == 1 && !canvas.dirty, region.revision);
		check("the section's cells", region.stateAt(region.cell(0, 0)) == STATES[0] && region.height[region.cell(255, 255)] == 70 && region.sourceAt(region.cell(100, 3)) == 2, "");
		check("beyond it", region.empty(region.cell(256, 0)) && region.sourceAt(region.cell(0, 300)) == LodRegion.NO_SOURCE, "");
		check("tile detail over the section and next to it", region.areaSource(16, 32, 16) == 2 && region.areaSource(256, 0, 16) == LodRegion.NO_SOURCE, "");
		byte[] coverage = region.coverage();
		check("coverage summary", coverage[0] == 2 && coverage[3 * 8 + 3] == 2 && coverage[4 * 8] == LodRegion.NO_SOURCE && coverage[7 * 8 + 7] == LodRegion.NO_SOURCE, "");
		check("coverage over a rectangle", LodRegion.coverageSource(coverage, 0, 0, 256, 256) == 2 && LodRegion.coverageSource(coverage, 0, 0, 512, 64) == LodRegion.NO_SOURCE, "");
		MapSection holes = section(2, 256, 256, STATES[1], 80);
		for (int i = 0; i < MapSection.SIZE; i += 2) holes.state[i] = null;
		changed = canvas.merge(holes, 2000L);
		check("empty pixels are skipped", changed == 64 * 32, changed);
	}

	static void coarseOverFine() {
		RegionCanvas canvas = RegionCanvas.empty(0, 0, 0);
		canvas.merge(section(0, 64, 64, STATES[0], 70), 0L);
		int changed = canvas.merge(section(3, 0, 0, STATES[1], 90), 0L);
		check("a coarse section fills only the empty cells", changed == 512 * 512 - 64 * 64, changed);
		LodRegion region = canvas.snapshot();
		check("the fine cells keep their data", region.stateAt(region.cell(64, 64)) == STATES[0] && region.sourceAt(region.cell(127, 127)) == 0, "");
		check("the rest has the coarse data", region.stateAt(region.cell(0, 0)) == STATES[1] && region.sourceAt(region.cell(400, 7)) == 3 && region.height[region.cell(511, 511)] == 90, "");
		check("max source is the coarse one", region.maxSource == 3, region.maxSource);
		check("tile detail: fine and coarse", region.areaSource(64, 64, 16) == 0 && region.areaSource(48, 64, 16) == 3, "");
		changed = canvas.merge(section(4, 0, 0, STATES[2], 99), 0L);
		check("an even coarser section changes nothing", changed == 0, changed);
	}

	static void finerSplits() {
		RegionCanvas canvas = RegionCanvas.empty(0, 0, 3);
		MapSection coarse = section(3, 0, 0, STATES[0], 60);
		// a pattern, so the split can be checked cell by cell
		for (int relX = 0; relX < 64; relX++) {
			for (int relZ = 0; relZ < 64; relZ++) {
				coarse.height[relX * 64 + relZ] = relX * 64 + relZ;
			}
		}
		canvas.merge(coarse, 0L);
		canvas.merge(section(1, 128, 256, STATES[1], 77), 0L);
		check("a finer section splits the cells", canvas.detail == 1 && canvas.cells == 256, canvas.detail);
		LodRegion region = canvas.snapshot();
		boolean same = true;
		for (int x = 0; x < 512; x += 3) {
			for (int z = 0; z < 512; z += 5) {
				int cell = region.cell(x, z);
				boolean inFine = x >= 128 && x < 256 && z >= 256 && z < 384;
				if (inFine) {
					if (region.height[cell] != 77 || region.sourceAt(cell) != 1) same = false;
				}
				else if (region.height[cell] != (x >> 3) * 64 + (z >> 3) || region.sourceAt(cell) != 3) same = false;
			}
		}
		check("every block shows what it showed before, the fine section where it is", same, "");
		check("max source still 3", region.maxSource == 3, region.maxSource);
	}

	static void huge() {
		// detail 11: one cell is 2048 blocks, the section 131072 blocks wide
		RegionCanvas canvas = RegionCanvas.empty(5, -7, 11);
		check("detail is capped at one cell per region", canvas.detail == LodRegion.MAX_DETAIL && canvas.cells == 1, canvas.detail);
		int span = 64 << 11;
		int originX = Math.floorDiv(5 * 512, span) * span, originZ = Math.floorDiv(-7 * 512, span) * span;
		int changed = canvas.merge(section(11, originX, originZ, STATES[3], 150), 0L);
		LodRegion region = canvas.snapshot();
		check("the huge section covers the region with one cell", changed == 1 && region.sourceAt(0) == 11 && region.stateAt(0) == STATES[3], changed);
		changed = canvas.merge(section(0, 5 * 512 + 64, -7 * 512, STATES[4], 64), 0L);
		check("a full detail section splits it all the way", canvas.detail == 0 && changed == 64 * 64, canvas.detail + " " + changed);
		region = canvas.snapshot();
		check("and the old data is everywhere else", region.stateAt(region.cell(0, 0)) == STATES[3] && region.sourceAt(region.cell(511, 511)) == 11 && region.stateAt(region.cell(64, 0)) == STATES[4], "");
		MapSection beside = section(2, 6 * 512, -7 * 512, STATES[5], 1);
		check("a section next to the region does not touch it", canvas.merge(beside, 0L) == 0, "");
	}

	static void negative() {
		RegionCanvas canvas = RegionCanvas.empty(-1, -1, 4);
		// a detail 4 section is 1024 blocks: the one from -1024 to 0 covers region (-1, -1) entirely
		int changed = canvas.merge(section(4, -1024, -1024, STATES[6], 20), 0L);
		check("negative coordinates: whole region", changed == 32 * 32, changed);
		MapSection marked = section(2, -256, -256, STATES[7], 30);
		marked.height[0] = 31;
		canvas.merge(marked, 0L);
		LodRegion region = canvas.snapshot();
		check("negative coordinates: the corner cell of the section", region.height[region.cell(256, 256)] == 31 && region.height[region.cell(260, 260)] == 30 && region.height[region.cell(255, 255)] == 20, "");
	}

	static void snapshots() {
		RegionCanvas canvas = RegionCanvas.empty(2, 3, 1);
		canvas.merge(section(1, 2 * 512, 3 * 512, STATES[0], 10), 0L);
		LodRegion first = canvas.snapshot();
		canvas.merge(section(0, 2 * 512, 3 * 512, STATES[1], 11), 0L);
		LodRegion second = canvas.snapshot();
		check("snapshots count up", first.revision == 1 && second.revision == 2, first.revision + " " + second.revision);
		check("an older snapshot does not change", first.detail == 1 && first.stateAt(first.cell(0, 0)) == STATES[0] && second.stateAt(second.cell(0, 0)) == STATES[1], "");
		RegionCanvas again = RegionCanvas.of(second);
		check("a canvas continued from a snapshot", again.revision == 2 && again.detail == 0 && again.maxSource() == second.maxSource && !again.dirty, again.revision);
		again.merge(section(0, 2 * 512 + 64, 3 * 512, STATES[2], 12), 0L);
		LodRegion third = again.snapshot();
		check("goes on from there", third.revision == 3 && third.stateAt(third.cell(64, 0)) == STATES[2] && third.stateAt(third.cell(0, 0)) == STATES[1] && third.stateAt(third.cell(100, 100)) == STATES[0], "");
		check("and shares nothing with it", second.stateAt(second.cell(64, 0)) == STATES[0], "");
	}

	static void equalDetail() {
		RegionCanvas canvas = RegionCanvas.empty(0, 0, 2);
		canvas.merge(section(2, 0, 0, STATES[0], 50), 0L);
		canvas.snapshot();
		int changed = canvas.merge(section(2, 0, 0, STATES[0], 50), 5L);
		check("the same section again changes nothing", changed == 0 && !canvas.dirty, changed);
		MapSection water = section(2, 0, 0, STATES[0], 50);
		water.overlay[5] = WATER;
		water.overlayDepth[5] = 3;
		water.biome[6] = OCEAN;
		changed = canvas.merge(water, 6L);
		check("a section of the same detail with other data replaces it", changed == 2 && canvas.dirty && canvas.changedAt == 6L, changed);
		LodRegion region = canvas.snapshot();
		int cell = region.cell(0, 5 << 2);
		check("its water and biome", region.overlayAt(cell) == WATER && (region.overlayDepth[cell] & 0xFF) == 3 && region.biomeAt(region.cell(0, 6 << 2)) == OCEAN, "");
	}

	/** Random sections in random order against a block-by-block model of what each block should show. */
	static void fuzz(Random random, int iterations) {
		for (int iteration = 0; iteration < iterations; iteration++) {
			int regionX = random.nextInt(9) - 4, regionZ = random.nextInt(9) - 4;
			int minX = regionX * 512, minZ = regionZ * 512;
			int[] modelState = new int[512 * 512], modelHeight = new int[512 * 512], modelSource = new int[512 * 512];
			java.util.Arrays.fill(modelState, -1);
			java.util.Arrays.fill(modelSource, LodRegion.NO_SOURCE);
			int expectedDetail = random.nextInt(10);
			RegionCanvas canvas = RegionCanvas.empty(regionX, regionZ, expectedDetail);
			int merges = 4 + random.nextInt(14);
			for (int merge = 0; merge < merges; merge++) {
				int detail = random.nextInt(12);
				int span = 64 << detail;
				int originX, originZ;
				if (span < 512) {
					originX = minX + random.nextInt(512 / span) * span;
					originZ = minZ + random.nextInt(512 / span) * span;
				}
				else {
					originX = (Math.floorDiv(minX, span) + (random.nextInt(8) == 0 ? 1 : 0)) * span;
					originZ = Math.floorDiv(minZ, span) * span;
				}
				MapSection section = new MapSection(OWNER, detail, originX, originZ, true);
				int base = random.nextInt(STATES.length);
				for (int i = 0; i < MapSection.SIZE; i++) {
					if (random.nextInt(10) == 0) continue;
					section.state[i] = STATES[(base + random.nextInt(3)) % STATES.length];
					section.height[i] = random.nextInt(300) - 50;
					section.topHeight[i] = section.height[i];
				}
				canvas.merge(section, merge);
				if (originX < minX + 512 && originX + span > minX && originZ < minZ + 512 && originZ + span > minZ) {
					expectedDetail = Math.min(expectedDetail, Math.min(detail, LodRegion.MAX_DETAIL));
				}
				// the model, block by block
				for (int x = 0; x < 512; x++) {
					int blockX = minX + x;
					if (blockX < originX || blockX >= originX + span) continue;
					int relX = (blockX - originX) >> detail;
					for (int z = 0; z < 512; z++) {
						int blockZ = minZ + z;
						if (blockZ < originZ || blockZ >= originZ + span) continue;
						int index = relX * 64 + ((blockZ - originZ) >> detail);
						if (section.state[index] == null) continue;
						int block = x * 512 + z;
						if (modelSource[block] < detail) continue;
						modelState[block] = indexOf(section.state[index]);
						modelHeight[block] = section.height[index];
						modelSource[block] = detail;
					}
				}
				if (merge == merges / 2) {
					// carry on from a stored copy halfway, as when a region is read back from the store
					canvas = RegionCanvas.of(canvas.snapshot());
				}
			}
			LodRegion region = canvas.snapshot();
			boolean blocks = true;
			int maxSource = 0;
			String first = "";
			for (int x = 0; x < 512 && blocks; x++) {
				for (int z = 0; z < 512; z++) {
					int block = x * 512 + z, cell = region.cell(x, z);
					maxSource = Math.max(maxSource, modelSource[block]);
					boolean ok = modelState[block] < 0
						? region.empty(cell) && region.sourceAt(cell) == LodRegion.NO_SOURCE
						: !region.empty(cell) && region.stateAt(cell) == STATES[modelState[block]] && region.height[cell] == modelHeight[block] && region.sourceAt(cell) == modelSource[block];
					if (!ok) {
						blocks = false;
						first = "block " + x + " " + z + ": model " + modelState[block] + "/" + modelHeight[block] + "/" + modelSource[block]
							+ ", region " + (region.empty(cell) ? "empty" : indexOf(region.stateAt(cell)) + "/" + region.height[cell]) + "/" + region.sourceAt(cell) + " at detail " + region.detail;
						break;
					}
				}
			}
			check("fuzz " + iteration + ": every block", blocks, first);
			if (!blocks) continue;
			check("fuzz " + iteration + ": coarsest source", region.maxSource == maxSource, region.maxSource + " vs " + maxSource);
			byte[] coverage = region.coverage();
			boolean squares = true;
			for (int square = 0; square < 64; square++) {
				int sx = (square / 8) * 64, sz = (square % 8) * 64, expected = 0;
				for (int x = sx; x < sx + 64; x++) {
					for (int z = sz; z < sz + 64; z++) expected = Math.max(expected, modelSource[x * 512 + z]);
				}
				if (coverage[square] != expected) squares = false;
			}
			check("fuzz " + iteration + ": coverage summary", squares, "");
			boolean tiles = true;
			for (int tile = 0; tile < 40; tile++) {
				int tx = random.nextInt(32) * 16, tz = random.nextInt(32) * 16, expected = 0;
				for (int x = tx; x < tx + 16; x++) {
					for (int z = tz; z < tz + 16; z++) expected = Math.max(expected, modelSource[x * 512 + z]);
				}
				if (region.areaSource(tx, tz, 16) != expected) tiles = false;
			}
			check("fuzz " + iteration + ": tile detail", tiles, "");
			check("fuzz " + iteration + ": the region's detail is that of its finest section", region.detail == expectedDetail, region.detail + " vs " + expectedDetail);
		}
	}

	static int indexOf(BlockState state) {
		for (int i = 0; i < STATES.length; i++) {
			if (STATES[i] == state) return i;
		}
		return -1;
	}

	static void check(String name, boolean ok, Object detail) {
		checks++;
		if (!ok) {
			failures++;
			if (failures <= 20) System.out.println("FAIL " + name + ": " + detail);
		}
	}
}
