package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.client.VertigoGuard;
import dev.seedgenglobe.dh.DhBridge;

/**
 * Where Distant Horizons takes in a loaded chunk to build LOD data from it:
 * <ul>
 * <li>with {@code dhChunkLodBuilding} off, it builds none in dimensions SeedGenGlobe generates, leaving its threads
 * to SeedGenGlobe's sections;</li>
 * <li>with Vertigo, it holds back client chunks whose synced sections do not contain their surface.</li>
 * </ul>
 */
@Mixin(value = SharedApi.class, remap = false)
public abstract class SharedApiMixin {

	@Inject(method = "applyChunkUpdate", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$gate(IChunkWrapper chunkWrapper, ILevelWrapper levelWrapper, CallbackInfo callback) {
		if (chunkWrapper == null || levelWrapper == null) return;
		GlobeConfig config = GlobeConfig.get();
		if (!config.dhChunkLodBuilding && DhBridge.generates(levelWrapper.getWrappedMcObject())) {
			callback.cancel();
			return;
		}
		if (ModCompat.CLIENT && ModCompat.VERTIGO && config.vertigoGuard && VertigoGuard.shouldHoldBack(chunkWrapper, levelWrapper)) {
			callback.cancel();
		}
	}
}
