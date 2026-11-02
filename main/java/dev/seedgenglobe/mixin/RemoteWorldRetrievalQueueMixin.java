package dev.seedgenglobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.generation.queues.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;

import dev.seedgenglobe.client.ClientGeneration;

/**
 * Answers the client's LOD section requests with locally generated Big Globe terrain instead of network
 * requests, and keeps DH from touching the (absent) network session while doing so.
 */
@Mixin(value = RemoteWorldRetrievalQueue.class, remap = false)
public abstract class RemoteWorldRetrievalQueueMixin extends AbstractFullDataNetworkRequestQueue {

	protected RemoteWorldRetrievalQueueMixin(ClientNetworkState networkState, DhClientLevel level, boolean changedOnly, ConfigEntry<Boolean> showDebugWireframeConfig) {
		super(networkState, level, changedOnly, showDebugWireframeConfig);
	}

	@Inject(method = "submitRetrievalTask", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$generateHere(long sectionPos, byte requiredDataDetail, CallbackInfoReturnable<CompletableFuture<DataSourceRetrievalResult>> callback) {
		CompletableFuture<DataSourceRetrievalResult> future = ClientGeneration.submit(this.level, this.networkState, sectionPos, requiredDataDetail);
		if (future != null) callback.setReturnValue(future);
	}

	@Inject(method = "startAndSetTargetPos", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$target(DhBlockPos2D targetPos, CallbackInfo callback) {
		if (ClientGeneration.setTarget(this.level, this.networkState, targetPos)) callback.cancel();
	}

	@Inject(method = "getRequestRateLimit", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$rateLimit(CallbackInfoReturnable<Integer> callback) {
		int limit = ClientGeneration.rateLimit(this.level, this.networkState);
		if (limit >= 0) callback.setReturnValue(limit);
	}
}
