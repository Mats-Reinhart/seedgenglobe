package dev.seedgenglobe.xaero;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What Xaero's World Map records for the top of one column: the block it shows and its height, up to two
 * see-through blocks above it (water, or ice over water) with their depth, the biome and the light.
 * Filled by {@link XaeroPixel#toPixel}, stored per cell by {@link LodRegion}.
 */
final class MapPixel {

	/** The block the map shows, or null when the column could not be computed. */
	@Nullable BlockState state;
	/** Height of that block (its own y), and of the highest thing drawn, which is the top of the water above it. */
	int height, topHeight;
	@Nullable ResourceKey<Biome> biome;
	/** The highest see-through block and how many blocks deep it is. */
	@Nullable BlockState overlay;
	int overlayDepth;
	/** The see-through block under it, such as the water under a frozen surface. */
	@Nullable BlockState overlay2;
	int overlayDepth2;
	byte light;

	void clear() {
		this.state = null;
		this.height = 0;
		this.topHeight = 0;
		this.biome = null;
		this.overlay = null;
		this.overlayDepth = 0;
		this.overlay2 = null;
		this.overlayDepth2 = 0;
		this.light = 0;
	}
}
