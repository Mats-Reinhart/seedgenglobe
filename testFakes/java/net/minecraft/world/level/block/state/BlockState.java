package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FluidState;

/** Test fake: the parts of BlockState that SeedGenGlobe's column logic uses, plus the light opacity Big Globe reads. */
public class BlockState {

	public final String name;
	public final boolean air, fluid;
	public final int light, opacity;
	public Block block;
	/** For waterlogged blocks: the block of the fluid they hold. Fluid blocks hold themselves. */
	public BlockState fluidBlock;

	public BlockState(String name, boolean air, boolean fluid, int light, int opacity) {
		this.name = name;
		this.air = air;
		this.fluid = fluid;
		this.light = light;
		this.opacity = opacity;
	}

	public boolean isAir() { return this.air; }
	public int getLightEmission() { return this.light; }
	public int getLightDampening() { return this.opacity; }
	/** Like vanilla: blocks that block no light and hold no fluid let sky light straight down. */
	public boolean propagatesSkylightDown() { return this.opacity == 0 && !this.fluid; }
	public FluidState getFluidState() { return new FluidState(!this.fluid, this.fluidBlock != null ? this.fluidBlock : this); }
	public Block getBlock() { return this.block; }
	@Override public String toString() { return this.name; }
}
