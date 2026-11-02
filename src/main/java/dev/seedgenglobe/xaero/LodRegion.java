package dev.seedgenglobe.xaero;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The surface map of one Xaero region (512 x 512 blocks): every cell holds what Xaero's World Map would record for
 * the top of that column, i.e. the block it shows, its height, the water (or ice over water) above it and the biome.
 * A cell covers 2^detail x 2^detail blocks.
 * <p>
 * The region is put together from LOD sections of different detail levels, so each cell also remembers the detail
 * level of the data it came from: the region's own, or a coarser one whose cell was copied into several of these.
 * <p>
 * An immutable snapshot of a {@link RegionCanvas}; built on the map builder thread, read by the map writer on the
 * render thread. Stored by {@link LodMapStore} in the format of {@link LodRegionCodec}.
 */
public final class LodRegion {

	public static final int REGION_BLOCKS = 512;
	/** One cell per region. */
	public static final int MAX_DETAIL = 9;
	static final short EMPTY = -1;
	/** Source detail of a cell without data; greater than any real detail level. */
	public static final int NO_SOURCE = 127;

	public final int regionX, regionZ, detail, cells;
	/** Counts up every time new data is saved for the region; the writer fills a region again when it changes. */
	public final long revision;
	/** The coarsest source over all cells, or {@link #NO_SOURCE} while any cell is still empty. */
	public final int maxSource;
	final BlockState[] statePalette;
	final ResourceKey<Biome>[] biomePalette;
	/** Index into {@link #statePalette}, or {@link #EMPTY} when the column could not be computed. */
	final short[] state;
	final short[] height;
	final short[] topHeight;
	/** Index into {@link #biomePalette}, -1 for none. */
	final short[] biome;
	/** Index into {@link #statePalette} of the see-through block above the surface (water, ice), -1 for none. */
	final short[] overlay;
	/** Depth of that overlay in blocks, capped at 255. */
	final byte[] overlayDepth;
	/** The see-through block under the first one (water under ice), -1 for none, and its depth. */
	final short[] overlay2;
	final byte[] overlayDepth2;
	final byte[] light;
	/** Detail level of the LOD section each cell was taken from ({@link #NO_SOURCE} when empty). */
	final byte[] source;

	LodRegion(int regionX, int regionZ, int detail, long revision, BlockState[] statePalette, ResourceKey<Biome>[] biomePalette, short[] state, short[] height, short[] topHeight, short[] biome, short[] overlay, byte[] overlayDepth, short[] overlay2, byte[] overlayDepth2, byte[] light, byte[] source) {
		this.regionX = regionX;
		this.regionZ = regionZ;
		this.detail = detail;
		this.cells = REGION_BLOCKS >> detail;
		this.revision = revision;
		this.statePalette = statePalette;
		this.biomePalette = biomePalette;
		this.state = state;
		this.height = height;
		this.topHeight = topHeight;
		this.biome = biome;
		this.overlay = overlay;
		this.overlayDepth = overlayDepth;
		this.overlay2 = overlay2;
		this.overlayDepth2 = overlayDepth2;
		this.light = light;
		this.source = source;
		this.maxSource = maxSource(source);
	}

	static int maxSource(byte[] source) {
		int max = 0;
		for (byte value : source) {
			if (value > max) {
				max = value;
				if (max >= NO_SOURCE) break;
			}
		}
		return max;
	}

	/** Cell index of the block at (x, z) relative to the region's corner (0..511). */
	int cell(int localX, int localZ) {
		return (localX >> this.detail) * this.cells + (localZ >> this.detail);
	}

	boolean empty(int cell) {
		return this.state[cell] == EMPTY;
	}

	BlockState stateAt(int cell) {
		return this.statePalette[this.state[cell]];
	}

	@Nullable BlockState overlayAt(int cell) {
		short index = this.overlay[cell];
		return index < 0 ? null : this.statePalette[index];
	}

	@Nullable BlockState overlay2At(int cell) {
		short index = this.overlay2[cell];
		return index < 0 ? null : this.statePalette[index];
	}

	@Nullable ResourceKey<Biome> biomeAt(int cell) {
		short index = this.biome[cell];
		return index < 0 ? null : this.biomePalette[index];
	}

	int sourceAt(int cell) {
		return this.source[cell];
	}

	/**
	 * The coarsest source detail among the cells under the block square (localX, localZ) .. +size, or
	 * {@link #NO_SOURCE} when any of them is empty. Xaero tiles are 16 blocks wide.
	 */
	int areaSource(int localX, int localZ, int size) {
		return areaSource(this.source, this.detail, this.cells, localX, localZ, localX + size, localZ + size);
	}

	/** 8 x 8 summary of the region: the coarsest source in each 64 block square (see {@link #coverage(byte[], int, int)}). */
	byte[] coverage() {
		return coverage(this.source, this.detail, this.cells);
	}

	/**
	 * The coarsest source among the cells covering the region-local block rectangle [minX, maxX) x [minZ, maxZ),
	 * or {@link #NO_SOURCE} when any of them is empty.
	 */
	static int areaSource(byte[] source, int detail, int cells, int minX, int minZ, int maxX, int maxZ) {
		int firstX = Math.max(minX, 0) >> detail, lastX = (Math.min(maxX, REGION_BLOCKS) - 1) >> detail;
		int firstZ = Math.max(minZ, 0) >> detail, lastZ = (Math.min(maxZ, REGION_BLOCKS) - 1) >> detail;
		int max = 0;
		for (int cellX = firstX; cellX <= lastX; cellX++) {
			int row = cellX * cells;
			for (int cellZ = firstZ; cellZ <= lastZ; cellZ++) {
				int value = source[row + cellZ];
				if (value > max) {
					max = value;
					if (max >= NO_SOURCE) return NO_SOURCE;
				}
			}
		}
		return max;
	}

	/** Width in blocks of the squares of a {@link #coverage} summary: the narrowest LOD section. */
	static final int COVERAGE_BLOCKS = 64;
	static final int COVERAGE_WIDTH = REGION_BLOCKS / COVERAGE_BLOCKS;

	/**
	 * The coarsest source in each of the 8 x 8 squares of 64 blocks of a region, index x * 8 + z: enough to tell,
	 * without the region's cells, whether a LOD section of some detail level would add anything to it.
	 */
	static byte[] coverage(byte[] source, int detail, int cells) {
		byte[] coverage = new byte[COVERAGE_WIDTH * COVERAGE_WIDTH];
		for (int x = 0; x < COVERAGE_WIDTH; x++) {
			for (int z = 0; z < COVERAGE_WIDTH; z++) {
				coverage[x * COVERAGE_WIDTH + z] = (byte)(areaSource(source, detail, cells, x * COVERAGE_BLOCKS, z * COVERAGE_BLOCKS, (x + 1) * COVERAGE_BLOCKS, (z + 1) * COVERAGE_BLOCKS));
			}
		}
		return coverage;
	}

	/** The coarsest value of a coverage summary over the region-local block rectangle [minX, maxX) x [minZ, maxZ). */
	static int coverageSource(byte[] coverage, int minX, int minZ, int maxX, int maxZ) {
		int firstX = Math.max(minX, 0) / COVERAGE_BLOCKS, lastX = (Math.min(maxX, REGION_BLOCKS) - 1) / COVERAGE_BLOCKS;
		int firstZ = Math.max(minZ, 0) / COVERAGE_BLOCKS, lastZ = (Math.min(maxZ, REGION_BLOCKS) - 1) / COVERAGE_BLOCKS;
		int max = 0;
		for (int x = firstX; x <= lastX; x++) {
			for (int z = firstZ; z <= lastZ; z++) {
				max = Math.max(max, coverage[x * COVERAGE_WIDTH + z]);
			}
		}
		return max;
	}

	/** Number of cells, for memory budgets. */
	int size() {
		return this.cells * this.cells;
	}

	public static long key(int regionX, int regionZ) {
		return ((long)(regionX) & 0xFFFFFFFFL) | ((long)(regionZ) << 32);
	}

	public static int keyX(long key) {
		return (int)(key);
	}

	public static int keyZ(long key) {
		return (int)(key >> 32);
	}
}
