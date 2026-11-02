package dev.seedgenglobe.xaero;

import java.util.HashSet;
import java.util.Set;

import xaero.map.region.MapTile;

/**
 * Checks the region bookkeeping shared by the LOD map builder and writer on the real compiled classes: the ring
 * order both of them walk (every region exactly once, nearest rings first), region keys, distances, the detail
 * level per distance, and which of Xaero's tiles LOD data may go into. See test-offline.sh.
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

	static void details() {
		expect("detail 0 near the player", LodMapBuilder.detailFor(0) == 0 && LodMapBuilder.detailFor(767.9D) == 0);
		expect("detail 1 from 768 blocks", LodMapBuilder.detailFor(768) == 1 && LodMapBuilder.detailFor(1535) == 1);
		expect("detail 2 from 1536 blocks", LodMapBuilder.detailFor(1536) == 2);
		expect("detail 3 from 3072 blocks", LodMapBuilder.detailFor(3072) == 3);
		expect("detail 5 from 12288 blocks", LodMapBuilder.detailFor(12288) == 5);
		expect("detail 6 at most", LodMapBuilder.detailFor(24576) == 6 && LodMapBuilder.detailFor(1.0e7D) == 6);
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
