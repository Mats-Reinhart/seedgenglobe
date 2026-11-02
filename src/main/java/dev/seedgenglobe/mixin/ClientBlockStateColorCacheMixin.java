package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;

import net.minecraft.world.level.block.state.BlockState;

import dev.seedgenglobe.client.GlobeColors;

/** Tints biome-coloured LOD blocks with Big Globe's per-position colours instead of DH's per-biome cache. */
@Mixin(value = ClientBlockStateColorCache.class, remap = false)
public abstract class ClientBlockStateColorCacheMixin {

	@Shadow @Final private IClientLevelWrapper clientLevelWrapper;
	@Shadow @Final private BlockState blockState;
	@Shadow @Final private BlockStateWrapper blockStateWrapper;
	@Shadow private int baseColor;
	@Shadow private boolean needPostTinting;
	@Shadow private int tintIndex;

	@Inject(method = "getColor", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$bigGlobeTint(BiomeWrapper biome, FullDataSourceV2 dataSource, DhBlockPos pos, CallbackInfoReturnable<Integer> callback) {
		if (!this.needPostTinting) return;
		int tint = GlobeColors.tint(this.clientLevelWrapper, this.blockState, this.tintIndex, pos.getX(), pos.getY(), pos.getZ(), biome, this.blockStateWrapper, dataSource);
		if (tint != -1) {
			callback.setReturnValue(ColorUtil.multiplyARGBwithRGB(this.baseColor, tint));
		}
	}
}
