package dev.seedgenglobe.mixin;

import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import dev.seedgenglobe.dh.DhQueueHook;
import dev.seedgenglobe.dh.DhRequestBoost;
import dev.seedgenglobe.dh.GlobeWorldGenerator;

/**
 * Lets Distant Horizons keep more sections in flight with SeedGenGlobe's generator ({@code dhRequestBoost}).
 * <p>
 * Distant Horizons starts a new section only while fewer than its thread count plus one are in progress. SeedGenGlobe
 * queues the sections it is handed and runs them on threads of its own, nearest to a player first, so it can take as
 * many as its queue holds: more sections to choose the nearest from, and no idle threads when Distant Horizons runs
 * fewer threads than SeedGenGlobe. Queues of other generators keep Distant Horizons' own rule.
 */
@Mixin(value = WorldGenerationQueue.class, remap = false)
public abstract class WorldGenerationQueueMixin implements DhQueueHook {

	@Shadow @Final private IDhApiWorldGenerator generator;
	@Shadow @Final private ConcurrentHashMap<Long, ?> inProgressGenTasksByLodPos;

	@Override
	public boolean seedgenglobe$generatedBySeedGenGlobe() {
		return this.generator instanceof GlobeWorldGenerator;
	}

	@Inject(method = "isGeneratorBusy()Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$moreInFlight(CallbackInfoReturnable<Boolean> callback) {
		if (!(this.generator instanceof GlobeWorldGenerator)) return;
		if (ThreadPoolUtil.getWorldGenExecutor() == null) return; // shutting down: Distant Horizons answers "busy"
		int limit = DhRequestBoost.inFlightLimit();
		if (limit > 0) callback.setReturnValue(this.inProgressGenTasksByLodPos.size() >= limit);
	}
}
