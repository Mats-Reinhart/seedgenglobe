package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;

import dev.seedgenglobe.dh.DhProviderHook;
import dev.seedgenglobe.dh.DhRequestBoost;

/**
 * Lets Distant Horizons keep asking for sections while its render loader and file queue are busy, in dimensions
 * SeedGenGlobe generates ({@code dhRequestBoost}).
 * <p>
 * Distant Horizons asks for new sections only while both queues hold fewer than five tasks per thread. Every finished
 * section adds render work, so when SeedGenGlobe generates quickly the render loader is soon over that limit and
 * Distant Horizons stops asking, leaving SeedGenGlobe's threads idle until the queue drains. Here it keeps asking
 * until the queues reach the memory load's limit instead (see {@link DhRequestBoost}).
 */
@Mixin(value = LodQuadTree.class, remap = false)
public abstract class LodQuadTreeMixin {

	@Shadow @Final private FullDataSourceProviderV2 fullDataSourceProvider;

	@WrapOperation(
		method = "updateAllRenderSections(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos2D;)V",
		at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/render/QuadTree/LodQuadTree;threadPoolCanAcceptWorldGenTasks()Z"),
		require = 0
	)
	private boolean seedgenglobe$askWhileQueuesDrain(Operation<Boolean> original) {
		if (original.call()) return true;
		return this.fullDataSourceProvider instanceof DhProviderHook provider
			&& provider.seedgenglobe$generatedBySeedGenGlobe()
			&& DhRequestBoost.queuesHaveRoom();
	}
}
