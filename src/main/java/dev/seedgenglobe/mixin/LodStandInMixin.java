package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;

import dev.seedgenglobe.dh.DhProviderHook;
import dev.seedgenglobe.dh.StandIns;

/**
 * Keeps Distant Horizons from drawing empty sections where coarser terrain is at hand, in dimensions SeedGenGlobe
 * generates (see {@link StandIns}).
 * <ul>
 * <li>When fewer than four children of a node can be drawn, DH draws the node instead and switches all four children
 * off. A node with no data counts as drawable too, so one child still loading blanked out the area of all four. Such
 * a node is no longer used as a stand-in: the children that loaded stay, and only the one still loading is blank for
 * a moment. A stand-in with terrain is used as before.</li>
 * <li>While Distant Horizons cannot generate (its Distant Generation is off), a section it wants that has no data will
 * stay empty, typically a finer level near terrain generated from farther away. It is then not drawn either, so DH
 * keeps drawing the coarser section around it, which has terrain. With generation on, DH's own rule applies: the
 * empty section is drawn and asked for, and is filled once it is generated.</li>
 * </ul>
 */
@Mixin(value = LodQuadTree.class, remap = false)
public abstract class LodStandInMixin {

	@Shadow @Final private IDhClientLevel level;
	@Shadow @Final private FullDataSourceProviderV2 fullDataSourceProvider;

	@Unique private boolean seedgenglobe$skipEmptyStandIns;
	@Unique private boolean seedgenglobe$skipEmptyWanted;

	/** Once per tree tick, on the tree's own thread, before it decides what to draw. */
	@Inject(method = "updateAllRenderSections(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos2D;)V", at = @At("HEAD"), require = 0)
	private void seedgenglobe$checkDimension(DhBlockPos2D playerPos, CallbackInfo callback) {
		boolean ours = StandIns.generatedHere(this.level);
		this.seedgenglobe$skipEmptyStandIns = ours;
		this.seedgenglobe$skipEmptyWanted = ours && this.fullDataSourceProvider instanceof DhProviderHook provider && !provider.seedgenglobe$canGenerateNow();
	}

	@WrapOperation(
		method = "onDetailLevelTooLow(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos2D;Lcom/seibel/distanthorizons/core/util/objects/quadTree/QuadNode;Lcom/seibel/distanthorizons/core/util/objects/quadTree/QuadNode;)Z",
		at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/render/QuadTree/LodRenderSection;canRender()Z"),
		require = 0
	)
	private boolean seedgenglobe$noEmptyStandIn(LodRenderSection section, Operation<Boolean> original) {
		if (!original.call(section)) return false;
		return !this.seedgenglobe$skipEmptyStandIns || StandIns.drawsSomething(section);
	}

	@WrapOperation(
		method = "onDesiredDetailLevel(Lcom/seibel/distanthorizons/core/util/objects/quadTree/QuadNode;Lcom/seibel/distanthorizons/core/util/objects/quadTree/QuadNode;)Z",
		at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/render/QuadTree/LodRenderSection;canRender()Z"),
		require = 0
	)
	private boolean seedgenglobe$noEmptyWanted(LodRenderSection section, Operation<Boolean> original) {
		if (!original.call(section)) return false;
		return !this.seedgenglobe$skipEmptyWanted || StandIns.drawsSomething(section);
	}
}
