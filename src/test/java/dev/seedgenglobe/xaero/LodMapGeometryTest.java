package dev.seedgenglobe.xaero;

import java.util.HashSet;
import java.util.Set;

import xaero.map.region.MapTile;

/**
 * Checks the region bookkeeping shared by the LOD map builder and writer on the real compiled classes: the ring
 * order both of them walk (every region exactly once, nearest rings first), region keys, distances, Distant Horizons'
 * detail level per distance and the sections it would show over a region (which cover it exactly once, finer
 * nearer the camera), and which of Xaero's tiles LOD data may go into. See test-offline.sh.
 */
public final class LodMapGeometryTest {

	static int checks, failures;

	public static void main(String[] args) {
		rings(0, 0);
		rings(-3, 5);
		rings(-40000, 39999);
		keys();
		distances();
		details();
		sections(0, 0, 0, 0);
		sections(0, 0, 3, -2);
		sections(1000, -700, -2, 1);
		sections(-30000, 25000, -59, 49);
		sections(100, 100, 40, 40);
		tiles();
		System.out.println("LodMapGeometryTest: " + checks + " checks, " + failures + " failures");
		if (failures > 0) System.exit(1);
	}

	static void rings(int centerX, int centerZ) {
		Set<Long> seen = new HashSet<>();
		boolean sizes = true, distances = true, unique = true;
		for (int ring = 0; ring <= 40; ring++) {
			int size = LodMapBuilder.ringSize(ring);
			if (size != (ring == 0 ? 1 : (2 * ring + 1) * (2 * ring + 1) - (2 * ring - 1) * (2 * ring - 1))) sizes = false;
			for (int step = 0; step < size; step++) {
				long key = LodMapBuilder.ringRegion(centerX, centerZ, ring, step);
				int x = LodRegion.keyX(key), z = LodRegion.keyZ(key);
				if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) != ring) distances = false;
				if (!seen.add(key)) unique = false;
			}
		}
		expect("rings around " + centerX + " " + centerZ + ": sizes are the square's perimeter", sizes);
		expect("rings around " + centerX + " " + centerZ + ": each region lies on its own ring", distances);
		expect("rings around " + centerX + " " + centerZ + ": no region twice", unique);
		expect("rings around " + centerX + " " + centerZ + ": the 81 x 81 square is covered", seen.size() == 81 * 81);
	}

	static void keys() {
		int[] values = { 0, 1, -1, 12345, -12345, Integer.MAX_VALUE / 512, Integer.MIN_VALUE / 512 };
		boolean ok = true;
		for (int x : values) {
			for (int z : values) {
				long key = LodRegion.key(x, z);
				if (LodRegion.keyX(key) != x || LodRegion.keyZ(key) != z) ok = false;
			}
		}
		expect("region keys round trip, negative ones included", ok);
	}

	static void distances() {
		expect("distance: inside the region", LodMapBuilder.distanceToRegion(100, 100, 0, 0) == 0.0D);
		expect("distance: to the next region east", LodMapBuilder.distanceToRegion(500, 0, 1, 0) == 12.0D);
		expect("distance: to the region west", LodMapBuilder.distanceToRegion(10, 0, -1, 0) == 11.0D);
		expect("distance: diagonal", Math.abs(LodMapBuilder.distanceToRegion(0, 0, 1, 1) - Math.sqrt(2.0D) * 512.0D) < 1.0e-9D);
	}

	/** Distant Horizons' "medium" horizontal quality: 12 chunks per step, each step twice as far. */
	static final LodMapBuilder.Scheme MEDIUM = LodMapBuilder.Scheme.MEDIUM;

	static void details() {
		expect("medium: detail 0 within 384 blocks", MEDIUM.detailAt(0) == 0 && MEDIUM.detailAt(100) == 0 && MEDIUM.detailAt(383.9D) == 0);
		expect("medium: detail 1 from 384 blocks", MEDIUM.detailAt(384) == 1 && MEDIUM.detailAt(767) == 1);
		expect("medium: detail 2 from 768 blocks", MEDIUM.detailAt(768) == 2);
		expect("medium: detail 4 from 3072 blocks", MEDIUM.detailAt(3072) == 4 && MEDIUM.detailAt(6143) == 4);
		expect("medium: detail 6 at most here", MEDIUM.detailAt(12288) == 6 && MEDIUM.detailAt(1.0e7D) == 6);
		LodMapBuilder.Scheme extreme = new LodMapBuilder.Scheme(32 * 16, Math.log(2.4D), 0);
		expect("extreme: detail 0 within 1228 blocks", extreme.detailAt(1228) == 0 && extreme.detailAt(1229) == 1);
		LodMapBuilder.Scheme chunks = new LodMapBuilder.Scheme(12 * 16, Math.log(2.0D), 4);
		expect("a coarser maximum resolution is the finest detail", chunks.detailAt(0) == 4 && chunks.detailAt(5000) == 4 && chunks.detailAt(7000) == 5);
	}

	/** The sections over a region tile it without gaps or overlaps, each at the detail its center asks for. */
	static void sections(int x, int z, int regionX, int regionZ) {
		java.util.List<int[]> sections = new java.util.ArrayList<>();
		LodMapBuilder.sectionsOver(regionX, regionZ, x, z, MEDIUM, sections);
		int[] covered = new int[512 * 512];
		boolean aligned = true, wanted = true;
		int minX = regionX * 512, minZ = regionZ * 512;
		for (int[] section : sections) {
			int span = 64 << section[2];
			if (Math.floorMod(section[0], span) != 0 || Math.floorMod(section[1], span) != 0) aligned = false;
			double cx = section[0] + span / 2.0D - x, cz = section[1] + span / 2.0D - z;
			int expected = MEDIUM.detailAt(Math.sqrt(cx * cx + cz * cz));
			if (section[2] > expected && section[2] > 0) wanted = false; // should have been split
			for (int bx = Math.max(section[0], minX); bx < Math.min(section[0] + span, minX + 512); bx++) {
				for (int bz = Math.max(section[1], minZ); bz < Math.min(section[1] + span, minZ + 512); bz++) {
					covered[(bx - minX) * 512 + (bz - minZ)]++;
				}
			}
		}
		boolean once = true;
		for (int count : covered) {
			if (count != 1) once = false;
		}
		String where = "region " + regionX + " " + regionZ + " seen from " + x + " " + z + " (" + sections.size() + " sections)";
		expect(where + ": sections are aligned to their size", aligned);
		expect(where + ": no section coarser than its center asks for", wanted);
		expect(where + ": every block covered exactly once", once);
	}

	static void tiles() {
		expect("no tile yet: writable", XaeroLodWriter.writable(null, 0));
		MapTile tile = new MapTile("overworld", 0, 0);
		expect("tile not loaded: writable", XaeroLodWriter.writable(tile, 3));
		tile.setLoaded(true);
		tile.setWorldInterpretationVersion(XaeroLodWriter.MARKER | 3);
		expect("LOD tile recognised", XaeroLodTiles.isLod(tile));
		expect("LOD tile: finer data replaces it", XaeroLodWriter.writable(tile, 2) && XaeroLodWriter.writable(tile, 0));
		expect("LOD tile: same or coarser data does not", !XaeroLodWriter.writable(tile, 3) && !XaeroLodWriter.writable(tile, 6));
		tile.setWorldInterpretationVersion(1);
		expect("Xaero's own tile is not a LOD tile", !XaeroLodTiles.isLod(tile));
		expect("Xaero's empty tile (Vertigo had not synced the chunk) takes LOD data", XaeroLodWriter.writable(tile, 6));
		tile.setWorldInterpretationVersion(0);
		expect("old Xaero tile is not a LOD tile", !XaeroLodTiles.isLod(tile));
	}

	static void expect(String what, boolean ok) {
		checks++;
		if (!ok) {
			failures++;
			System.out.println("FAILED: " + what);
		}
	}
}
