package dev.seedgenglobe.structures;

import java.util.function.Function;
import java.util.random.RandomGenerator;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.FluidState;

import builderb0y.bigglobe.features.RawFeature;
import builderb0y.bigglobe.features.SingleBlockFeature;
import builderb0y.bigglobe.scripting.wrappers.WorldWrapper;
import builderb0y.bigglobe.util.WorldOrChunk;
import builderb0y.bigglobe.util.coordinators.Coordinator;

/**
 * What Big Globe's raw-generation structure scripts write into here: a stand-in for the chunk they normally get
 * during noise generation (Big Globe's ChunkDelegator), backed by the section being generated. It refuses what a
 * chunk refuses at that stage (entities, templates, ordinary features).
 */
final class CaptureWorld implements WorldOrChunk {

	/** Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE, what Big Globe places blocks with */
	static final int UPDATE_FLAGS = 2 | 16;

	final CaptureView view;
	final CaptureLevel level;
	final long seed;
	/** The script environment currently writing here, for raw features (set before each placement). */
	@Nullable WorldWrapper wrapper;

	CaptureWorld(CaptureView view, CaptureLevel level, long seed) {
		this.view = view;
		this.level = level;
		this.seed = seed;
	}

	@Override
	public int getMinY() {
		return this.view.levelMinY;
	}

	@Override
	public int getHeight() {
		return this.view.levelMaxY - this.view.levelMinY;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return this.view.state(pos.getX(), pos.getY(), pos.getZ());
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return this.getBlockState(pos).getFluidState();
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public void setBlockState(BlockPos pos, BlockState state) {
		this.view.write(pos.getX(), pos.getY(), pos.getZ(), state);
	}

	@Override
	public boolean placeBlockState(BlockPos pos, BlockState state) {
		if (!SingleBlockFeature.IS_REPLACEABLE.test(this.getBlockState(pos))) return false;
		this.setBlockState(pos, state);
		return true;
	}

	@Override
	public void updateBlockState(BlockPos pos) {}

	@Override
	public boolean canPlace(BlockPos pos, BlockState state) {
		return true;
	}

	@Override
	public void scheduleFluidTick(BlockPos pos, FluidState state) {}

	@Override
	public long getSeed() {
		return this.seed;
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean placeFeature(BlockPos pos, ConfiguredFeature<?, ?> feature, RandomSource random) {
		if (feature.feature() instanceof RawFeature rawFeature && this.wrapper != null) {
			return rawFeature.generate(this.wrapper, feature.config(), pos);
		}
		throw new UnsupportedOperationException("The provided feature cannot generate during raw generation.");
	}

	@Override
	public void spawnEntity(Function<ServerLevel, Entity> entitySupplier) {
		throw new UnsupportedOperationException("Can't spawn entities during raw generation.");
	}

	@Override
	public Coordinator coordinator() {
		return Coordinator.forWorld(this.level.world(), UPDATE_FLAGS);
	}

	@Override
	public void placeStructureTemplate(int x, int y, int z, StructureTemplate template, StructurePlaceSettings data, RandomGenerator random) {
		throw new UnsupportedOperationException("Can't place structure templates during raw generation.");
	}
}
