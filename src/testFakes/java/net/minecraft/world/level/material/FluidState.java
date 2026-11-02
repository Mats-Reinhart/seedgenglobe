package net.minecraft.world.level.material;

import net.minecraft.world.level.block.state.BlockState;

public class FluidState {

	private final boolean empty;
	private final BlockState legacy;

	public FluidState(boolean empty) {
		this(empty, null);
	}

	public FluidState(boolean empty, BlockState legacy) {
		this.empty = empty;
		this.legacy = legacy;
	}

	public boolean isEmpty() { return this.empty; }

	/** The block a fluid is drawn as: the fluid's own block (water for waterlogged blocks). */
	public BlockState createLegacyBlock() { return this.legacy; }
}
