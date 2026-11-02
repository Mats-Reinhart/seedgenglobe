package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

import dev.seedgenglobe.client.VertigoGuard;

/** With Vertigo, holds back client chunks whose synced sections do not contain their surface. */
@Mixin(value = SharedApi.class, remap = false)
public abstract class SharedApiMixin {

	@Inject(method = "applyChunkUpdate", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$vertigoGuard(IChunkWrapper chunkWrapper, ILevelWrapper levelWrapper, CallbackInfo callback) {
		if (chunkWrapper != null && VertigoGuard.shouldHoldBack(chunkWrapper, levelWrapper)) {
			callback.cancel();
		}
	}
}
