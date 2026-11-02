package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import xaero.map.MapWriter;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

import dev.seedgenglobe.xaero.XaeroLodTiles;

/**
 * Lets Xaero's World Map map a real chunk over SeedGenGlobe's LOD tile at once.
 * <p>
 * Xaero's writer only rewrites a tile it already has when the chunk changed and its "update chunks" option is on,
 * but always writes a tile it does not have yet. A LOD tile is a stand-in until the real chunk is seen, so it is
 * reported as missing to the writer: Xaero then writes the chunk into a fresh tile that replaces it. Nothing
 * changes while the chunk is not loaded on the client, since Xaero writes no tile for such a chunk.
 */
@Mixin(value = MapWriter.class, remap = false)
public abstract class XaeroLodTileMixin {

	@WrapOperation(
		method = "writeChunk",
		at = @At(value = "INVOKE", target = "Lxaero/map/region/MapTileChunk;getTile(II)Lxaero/map/region/MapTile;", ordinal = 0),
		require = 0
	)
	private MapTile seedgenglobe$replaceLodTiles(MapTileChunk tileChunk, int x, int z, Operation<MapTile> original) {
		MapTile tile = original.call(tileChunk, x, z);
		return tile != null && XaeroLodTiles.isLod(tile) ? null : tile;
	}
}
