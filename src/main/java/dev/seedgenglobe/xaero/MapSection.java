package dev.seedgenglobe.xaero;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import dev.seedgenglobe.terrain.SampledColumns;

/**
 * The map pixels of one LOD section, 64 x 64 cells of 2^detail blocks: what Xaero's World Map would draw for each
 * of its columns. Made on the thread that generated the section (Distant Horizons' generation, or the map builder's
 * own), then merged into {@link RegionCanvas regions} on the map builder thread.
 */
final class MapSection {

	static final int WIDTH = SampledColumns.WIDTH;
	static final int SIZE = WIDTH * WIDTH;

	/** The map builder target this was made for; sections of an older target are dropped. */
	final Object owner;
	final int detail, originX, originZ;
	/** Generated for Distant Horizons (as opposed to computed by the map builder for the map only). */
	final boolean live;
	final BlockState[] state = new BlockState[SIZE];
	final int[] height = new int[SIZE];
	final int[] topHeight = new int[SIZE];
	@SuppressWarnings("unchecked")
	final ResourceKey<Biome>[] biome = new ResourceKey[SIZE];
	final BlockState[] overlay = new BlockState[SIZE];
	final int[] overlayDepth = new int[SIZE];
	final BlockState[] overlay2 = new BlockState[SIZE];
	final int[] overlayDepth2 = new int[SIZE];
	final byte[] light = new byte[SIZE];

	MapSection(Object owner, int detail, int originX, int originZ, boolean live) {
		this.owner = owner;
		this.detail = detail;
		this.originX = originX;
		this.originZ = originZ;
		this.live = live;
	}

	/** Width of the section in blocks. */
	int span() {
		return WIDTH << this.detail;
	}

	/** Reads every column the way Xaero reads a chunk column. */
	void fill(SampledColumns columns, XaeroPixel.Rules rules) {
		MapPixel pixel = new MapPixel();
		for (int relX = 0; relX < WIDTH; relX++) {
			for (int relZ = 0; relZ < WIDTH; relZ++) {
				XaeroPixel.toPixel(columns.profile(relX, relZ), rules, pixel);
				this.store(relX * WIDTH + relZ, pixel);
			}
		}
	}

	void store(int index, MapPixel pixel) {
		this.state[index] = pixel.state;
		this.height[index] = pixel.height;
		this.topHeight[index] = pixel.topHeight;
		this.biome[index] = pixel.biome;
		this.overlay[index] = pixel.overlay;
		this.overlayDepth[index] = pixel.overlayDepth;
		this.overlay2[index] = pixel.overlay != null ? pixel.overlay2 : null;
		this.overlayDepth2[index] = pixel.overlayDepth2;
		this.light[index] = pixel.light;
	}

	@Nullable BlockState stateAt(int relX, int relZ) {
		return this.state[relX * WIDTH + relZ];
	}
}
