package net.minecraft.world.level.block;

/** Test fake. Opacity values follow vanilla light dampening: 15 for full blocks, 1 for water and leaves, 0 for air. */
public class Blocks {
	public static final Block AIR = new Block("air", true, false, 0, 0);
	public static final Block SNOW = new Block("snow", false, false, 0, 0);
	public static final Block STONE = new Block("stone", false, false, 0, 15);
	public static final Block DIRT = new Block("dirt", false, false, 0, 15);
	public static final Block GRASS_BLOCK = new Block("grass_block", false, false, 0, 15);
	public static final Block SAND = new Block("sand", false, false, 0, 15);
	public static final Block WATER = new Block("water", false, true, 0, 1);
	public static final Block OAK_LEAVES = new Block("oak_leaves", false, false, 0, 1);
	public static final Block OAK_LOG = new Block("oak_log", false, false, 0, 15);
	public static final Block GLOWSTONE = new Block("glowstone", false, false, 15, 15);
	public static final Block BEDROCK = new Block("bedrock", false, false, 0, 15);
}
