package dev.seedgenglobe.xaero;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/** Recognises the tiles SeedGenGlobe wrote into Xaero's World Map from LOD data. */
public final class XaeroLodTiles {

	/**
	 * Whether a tile chunk was LOD only the last time its tiles were in memory. Xaero keeps just the texture of
	 * regions it is not writing to, so the tiles are not always there to look at.
	 */
	private static final Map<MapTileChunk, Boolean> LOD_ONLY = Collections.synchronizedMap(new WeakHashMap<>());

	private XaeroLodTiles() {}

	/** A tile written from LOD data: its world interpretation version is {@code 0xC0 | detail} (Xaero's own are 0 and 1). */
	public static boolean isLod(MapTile tile) {
		return (tile.getWorldInterpretationVersion() & 0xF0) == XaeroLodWriter.MARKER;
	}

	/**
	 * Whether a tile chunk (4 x 4 chunks) holds LOD tiles and nothing Xaero mapped itself. While its tiles are not in
	 * memory, the answer from the last time they were is used; a tile chunk never seen with its tiles counts as real.
	 */
	public static boolean lodOnly(MapTileChunk chunk) {
		if (chunk.getLoadState() != 2) {
			Boolean known = LOD_ONLY.get(chunk);
			return known != null && known;
		}
		boolean lod = false;
		scan:
		for (int x = 0; x < 4; x++) {
			for (int z = 0; z < 4; z++) {
				MapTile tile = chunk.getTile(x, z);
				if (tile == null || !tile.isLoaded()) continue;
				if (!isLod(tile)) {
					lod = false;
					break scan;
				}
				lod = true;
			}
		}
		Boolean known = LOD_ONLY.get(chunk);
		if (known == null || known != lod) LOD_ONLY.put(chunk, lod);
		return lod;
	}
}
