package dev.seedgenglobe.client;

import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.common.wrappers.block.TintWithoutLevelOverrider;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import builderb0y.bigglobe.ClientState;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;

/**
 * Gives Distant Horizons LODs Big Globe's own grass, foliage and water colours.
 * <p>
 * Big Globe computes those colours per position from its climate noise (through a mixin on
 * {@code BiomeColors.getAverageColor}) instead of using flat per-biome colours. Distant Horizons tints LODs per
 * biome and caches one colour per block and biome, so far terrain shows hard colour steps at biome borders that
 * real chunks do not have. Here a tint getter that carries the client level is handed to the block's tint source;
 * Big Globe recognises the level and answers with its scripted colour for that exact position.
 */
public final class GlobeColors {

	private static final ThreadLocal<LevelTintGetter> GETTERS = ThreadLocal.withInitial(LevelTintGetter::new);
	private static volatile boolean failed;

	private GlobeColors() {}

	/**
	 * DH's tint getter plus the one thing Big Globe needs to recognise the world: a field holding the level.
	 * Big Globe finds that field by reflection ({@code DimensionalBlockView}), so it must stay the only Level field.
	 */
	public static final class LevelTintGetter extends TintWithoutLevelOverrider {

		public ClientLevel level;
		final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
	}

	public static boolean enabled() {
		return GlobeConfig.get().bigGlobeColors && !failed;
	}

	/**
	 * @return the tint to multiply into the block's base colour, or -1 to let Distant Horizons colour it as usual.
	 */
	public static int tint(
		IClientLevelWrapper levelWrapper,
		BlockState state,
		int tintIndex,
		int x,
		int y,
		int z,
		BiomeWrapper biome,
		BlockStateWrapper blockWrapper,
		FullDataSourceV2 dataSource
	) {
		if (!enabled()) return -1;
		try {
			if (!(levelWrapper.getWrappedMcObject() instanceof ClientLevel level)) return -1;
			ClientState clientState = ClientState.get(level.dimension());
			if (clientState == null || clientState.generatorParams == null || clientState.generatorParams.colors == null) return -1;
			BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
			if (source == null) return -1;
			LevelTintGetter getter = GETTERS.get();
			getter.level = level;
			getter.update(biome, blockWrapper, dataSource, levelWrapper);
			getter.mutablePos.set(x, y, z);
			return source.colorInWorld(state, getter, getter.mutablePos);
		}
		catch (Throwable throwable) {
			if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
			failed = true;
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: Big Globe colours for LODs are switched off after an error; LODs use per-biome colours.", throwable);
			return -1;
		}
	}
}
