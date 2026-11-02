package dev.seedgenglobe.xaero;

import xaero.map.region.MapTile;

/** Recognises the tiles SeedGenGlobe wrote into Xaero's World Map from LOD data. */
public final class XaeroLodTiles {

	private XaeroLodTiles() {}

	/** A tile written from LOD data: its world interpretation version is {@code 0xC0 | detail} (Xaero's own are 0 and 1). */
	public static boolean isLod(MapTile tile) {
		return (tile.getWorldInterpretationVersion() & 0xF0) == XaeroLodWriter.MARKER;
	}
}
