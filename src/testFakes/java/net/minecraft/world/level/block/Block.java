package net.minecraft.world.level.block;

import net.minecraft.world.level.block.state.BlockState;

public class Block {

	private final BlockState state;

	public Block(String name, boolean air, boolean fluid, int light, int opacity) {
		this.state = new BlockState(name, air, fluid, light, opacity);
		this.state.block = this;
	}

	public BlockState defaultBlockState() { return this.state; }
}
