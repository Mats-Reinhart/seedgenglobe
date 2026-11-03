package dev.seedgenglobe.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.QuadTree.QuadTreeTickNodeHolder;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;

import dev.seedgenglobe.dh.StandIns;

/**
 * Lets Distant Horizons ask SeedGenGlobe for the coarser sections it draws in place of finer ones, not only for the
 * sections it wants at their own level ({@code generateParentSections}, see {@link StandIns}).
 * <p>
 * DH hands the nodes it wants generated to its retrieval thread, which asks the data provider which of them are missing
 * and queues those. Adding their ancestors to that list goes through the same checks, so an ancestor already in the
 * database is not generated again, and DH drops the requests of nodes that leave its tree as usual.
 */
@Mixin(value = LodQuadTree.class, remap = false)
public abstract class LodParentRequestMixin {

	@Shadow @Final private FullDataSourceProviderV2 fullDataSourceProvider;
	@Shadow @Final private IDhClientLevel level;

	@WrapOperation(
		method = "updateAllRenderSections(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos2D;)V",
		at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/render/QuadTree/QuadTreeTickNodeHolder;getWorldGenNodesNearToFar(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos2D;)Ljava/util/ArrayList;"),
		require = 0
	)
	private ArrayList<QuadNode<LodRenderSection>> seedgenglobe$withParents(QuadTreeTickNodeHolder holder, DhBlockPos2D playerPos, Operation<ArrayList<QuadNode<LodRenderSection>>> original) {
		ArrayList<QuadNode<LodRenderSection>> nodes = original.call(holder, playerPos);
		if (!StandIns.requestParents(this.fullDataSourceProvider, this.level)) return nodes;
		return StandIns.withParents((LodQuadTree)(Object)(this), nodes);
	}
}
