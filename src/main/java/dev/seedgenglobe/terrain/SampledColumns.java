package dev.seedgenglobe.terrain;

import net.minecraft.world.level.block.state.BlockState;

/** The columns of one LOD section as a sampler computed them, for what is placed into them afterwards. */
public interface SampledColumns {

	/** LOD columns per section side. */
	int WIDTH = 64;

	/** The column of LOD cell (relX, relZ), both 0 to {@link #WIDTH} - 1. */
	ColumnProfile profile(int relX, int relZ);

	BlockState air();

	/** How far a cell's sample point lies from its minimum corner at a detail level, in blocks. */
	int halfCell(int detail);
}
