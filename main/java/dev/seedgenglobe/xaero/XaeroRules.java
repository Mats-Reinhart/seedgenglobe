package dev.seedgenglobe.xaero;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import xaero.map.MapWriter;

/**
 * Xaero's own rules for which blocks show on the map, taken from its {@code MapWriter} so the LOD map treats
 * grass, flowers, glass, water and ice exactly like the chunks Xaero maps itself. Results are cached per state.
 * Xaero calls the same methods from its region loading thread, so they are safe off the render thread.
 */
final class XaeroRules implements XaeroPixel.Rules {

	private final MapWriter writer;
	private final Level level;
	private final Registry<Block> blocks;
	final boolean flowers;
	private final ConcurrentHashMap<BlockState, Byte> states = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<FluidState, Byte> fluids = new ConcurrentHashMap<>();

	XaeroRules(MapWriter writer, Level level, Registry<Block> blocks, boolean flowers) {
		this.writer = writer;
		this.level = level;
		this.blocks = blocks;
		this.flowers = flowers;
	}

	@Override
	public byte classify(BlockState state) {
		Byte known = this.states.get(state);
		if (known != null) return known;
		byte kind;
		try {
			if (this.writer.isInvisible(state, state.getBlock(), this.flowers)) kind = SKIP;
			else if (this.writer.shouldOverlay(state)) kind = OVERLAY;
			else if (!this.writer.hasVanillaColor(state, this.level, this.blocks, BlockPos.ZERO)) kind = SKIP;
			else kind = OPAQUE;
		}
		catch (RuntimeException exception) {
			kind = OPAQUE;
		}
		this.states.put(state, kind);
		return kind;
	}

	@Override
	public byte classifyFluid(FluidState fluid, BlockState fluidBlock) {
		Byte known = this.fluids.get(fluid);
		if (known != null) return known;
		byte kind;
		try {
			if (this.writer.isInvisible(fluidBlock, fluidBlock.getBlock(), this.flowers)) kind = SKIP;
			else if (this.writer.shouldOverlay(fluid)) kind = OVERLAY;
			else if (!this.writer.hasVanillaColor(fluidBlock, this.level, this.blocks, BlockPos.ZERO)) kind = SKIP;
			else kind = OPAQUE;
		}
		catch (RuntimeException exception) {
			kind = OVERLAY;
		}
		this.fluids.put(fluid, kind);
		return kind;
	}
}
