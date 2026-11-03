package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;

import dev.seedgenglobe.client.ClientGeneration;
import dev.seedgenglobe.dh.DhClientLevelHook;

/**
 * Distant Horizons 3.0 only runs world generation for a client level when a DH server is connected.
 * Without one, SeedGenGlobe can still generate Big Globe terrain locally, so it switches generation on.
 */
@Mixin(value = DhClientLevel.class, remap = false)
public abstract class DhClientLevelMixin implements DhClientLevelHook {

	@Shadow @Final private ClientNetworkState networkState;

	@Inject(method = "shouldDoWorldGen", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$generateLocally(CallbackInfoReturnable<Boolean> callback) {
		if (ClientGeneration.shouldGenerate((DhClientLevel)(Object)(this), this.networkState)) {
			callback.setReturnValue(Boolean.TRUE);
		}
	}

	@Override
	public boolean seedgenglobe$generatesLocally() {
		return ClientGeneration.answersLocally((DhClientLevel)(Object)(this), this.networkState);
	}
}
