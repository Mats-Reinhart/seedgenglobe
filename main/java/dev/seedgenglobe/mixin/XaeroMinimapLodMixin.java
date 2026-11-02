package dev.seedgenglobe.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.common.mods.SupportXaeroWorldmap;
import xaero.map.region.MapTileChunk;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.xaero.XaeroLodTiles;

/**
 * Keeps SeedGenGlobe's LOD tiles off Xaero's Minimap while {@code xaeroMinimapLodMap} is off, leaving them on the
 * World Map.
 * <p>
 * The minimap draws the World Map's textures 64 x 64 blocks at a time (one tile chunk) and cannot leave out part of
 * one, so a tile chunk is skipped only when every chunk in it is a LOD tile: explored land keeps a fringe of LOD
 * tiles at most one tile chunk wide.
 */
@Mixin(value = SupportXaeroWorldmap.class, remap = false)
public abstract class XaeroMinimapLodMixin {

	@Inject(method = "prepareMapTexturedRect", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$hideLodTiles(Matrix4f matrix, float x, float y, int textureX, int textureY, float width, float height,
		MapTileChunk chunk, MultiTextureRenderTypeRenderer renderer, MultiTextureRenderTypeRenderer lightRenderer, MinimapRendererHelper helper, CallbackInfo info) {
		if (!GlobeConfig.get().xaeroMinimapLodMap && chunk != null && XaeroLodTiles.lodOnly(chunk)) info.cancel();
	}
}
