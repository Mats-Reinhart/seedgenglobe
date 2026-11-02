package dev.seedgenglobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.core.generation.queues.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.pos.DhSectionPos;

import dev.seedgenglobe.client.ClientGeneration;

/**
 * Makes the queue report SeedGenGlobe's own waiting and running sections, so Distant Horizons throttles its
 * requests correctly and shows real numbers in its F3 lines, and lets DH cancel and close them.
 */
@Mixin(value = AbstractFullDataNetworkRequestQueue.class, remap = false)
public abstract class NetworkRequestQueueMixin {

	@Shadow @Final protected DhClientLevel level;
	@Shadow @Final public ClientNetworkState networkState;

	private boolean seedgenglobe$isWorldQueue() {
		return (Object)(this) instanceof RemoteWorldRetrievalQueue;
	}

	@Inject(method = "getWaitingTaskCount", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$waiting(CallbackInfoReturnable<Integer> callback) {
		if (!this.seedgenglobe$isWorldQueue()) return;
		int count = ClientGeneration.waitingCount(this.level, this.networkState);
		if (count >= 0) callback.setReturnValue(count);
	}

	@Inject(method = "getInProgressTaskCount", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$running(CallbackInfoReturnable<Integer> callback) {
		if (!this.seedgenglobe$isWorldQueue()) return;
		int count = ClientGeneration.runningCount(this.level, this.networkState);
		if (count >= 0) callback.setReturnValue(count);
	}

	@Inject(method = "removeRetrievalRequestIf", at = @At("HEAD"), require = 0)
	private void seedgenglobe$prune(DhSectionPos.ICancelablePrimitiveLongConsumer removeIf, CallbackInfo callback) {
		if (this.seedgenglobe$isWorldQueue()) ClientGeneration.removeIf(this.level, this.networkState, removeIf);
	}

	@Inject(method = "startClosingAsync", at = @At("HEAD"), require = 0)
	private void seedgenglobe$closing(boolean alsoInterruptRunning, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
		if (this.seedgenglobe$isWorldQueue()) ClientGeneration.closing(this.level);
	}
}
