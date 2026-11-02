package dev.seedgenglobe.xaero;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One region of the LOD map while sections are being merged into it, on the map builder thread only.
 * <p>
 * Sections arrive at any detail level and in any order. A cell keeps the data of the finest section that covered
 * it: a coarser section only fills cells that are empty or hold even coarser data, and a finer one replaces what is
 * there. The region's own detail is that of the finest section merged so far; when a finer one arrives, every cell
 * is first split into four (or more) cells holding the same data, which remember the coarser detail they came from.
 * {@link #snapshot()} hands an immutable copy to the store and the map writer.
 */
final class RegionCanvas {

	final int regionX, regionZ;
	/** Block coordinates of the region's corner. */
	final int minX, minZ;
	int detail, cells;
	/** Revision of the last snapshot. */
	long revision;

	private final Map<BlockState, Short> states = new HashMap<>();
	private final List<BlockState> statePalette = new ArrayList<>();
	private final Map<ResourceKey<Biome>, Short> biomes = new HashMap<>();
	private final List<ResourceKey<Biome>> biomePalette = new ArrayList<>();
	short[] state, height, topHeight, biome, overlay, overlay2;
	byte[] overlayDepth, overlayDepth2, light, source;

	/** Changed since the last snapshot; when that started and when the last change was (milliseconds). */
	boolean dirty;
	long dirtySince, changedAt;
	/** Last merge or look-up, for evicting idle canvases. */
	long usedAt;
	private int maxSource = LodRegion.NO_SOURCE;
	private boolean maxSourceKnown;

	private RegionCanvas(int regionX, int regionZ, int detail) {
		this.regionX = regionX;
		this.regionZ = regionZ;
		this.minX = regionX * LodRegion.REGION_BLOCKS;
		this.minZ = regionZ * LodRegion.REGION_BLOCKS;
		this.detail = detail;
		this.cells = LodRegion.REGION_BLOCKS >> detail;
	}

	static RegionCanvas empty(int regionX, int regionZ, int detail) {
		RegionCanvas canvas = new RegionCanvas(regionX, regionZ, Math.max(0, Math.min(detail, LodRegion.MAX_DETAIL)));
		int size = canvas.size();
		canvas.allocate(size);
		Arrays.fill(canvas.state, LodRegion.EMPTY);
		Arrays.fill(canvas.biome, (short)(-1));
		Arrays.fill(canvas.overlay, (short)(-1));
		Arrays.fill(canvas.overlay2, (short)(-1));
		Arrays.fill(canvas.source, (byte)(LodRegion.NO_SOURCE));
		canvas.maxSourceKnown = true;
		return canvas;
	}

	/** A canvas continuing from a stored region. */
	static RegionCanvas of(LodRegion region) {
		RegionCanvas canvas = new RegionCanvas(region.regionX, region.regionZ, region.detail);
		canvas.revision = region.revision;
		for (BlockState blockState : region.statePalette) {
			canvas.states.putIfAbsent(blockState, (short)(canvas.statePalette.size()));
			canvas.statePalette.add(blockState);
		}
		for (ResourceKey<Biome> key : region.biomePalette) {
			canvas.biomes.putIfAbsent(key, (short)(canvas.biomePalette.size()));
			canvas.biomePalette.add(key);
		}
		canvas.state = region.state.clone();
		canvas.height = region.height.clone();
		canvas.topHeight = region.topHeight.clone();
		canvas.biome = region.biome.clone();
		canvas.overlay = region.overlay.clone();
		canvas.overlayDepth = region.overlayDepth.clone();
		canvas.overlay2 = region.overlay2.clone();
		canvas.overlayDepth2 = region.overlayDepth2.clone();
		canvas.light = region.light.clone();
		canvas.source = region.source.clone();
		canvas.maxSource = region.maxSource;
		canvas.maxSourceKnown = true;
		return canvas;
	}

	private void allocate(int size) {
		this.state = new short[size];
		this.height = new short[size];
		this.topHeight = new short[size];
		this.biome = new short[size];
		this.overlay = new short[size];
		this.overlayDepth = new byte[size];
		this.overlay2 = new short[size];
		this.overlayDepth2 = new byte[size];
		this.light = new byte[size];
		this.source = new byte[size];
	}

	int size() {
		return this.cells * this.cells;
	}

	private short stateIndex(BlockState blockState) {
		Short index = this.states.get(blockState);
		if (index == null) {
			index = (short)(this.statePalette.size());
			this.statePalette.add(blockState);
			this.states.put(blockState, index);
		}
		return index;
	}

	private short biomeIndex(@Nullable ResourceKey<Biome> key) {
		if (key == null) return -1;
		Short index = this.biomes.get(key);
		if (index == null) {
			index = (short)(this.biomePalette.size());
			this.biomePalette.add(key);
			this.biomes.put(key, index);
		}
		return index;
	}

	/** Splits every cell so the region has the given (finer) detail; the data and its source stay the same. */
	void regrid(int finer) {
		if (finer >= this.detail) return;
		int shift = this.detail - finer;
		int oldCells = this.cells;
		short[] state = this.state, height = this.height, topHeight = this.topHeight, biome = this.biome, overlay = this.overlay, overlay2 = this.overlay2;
		byte[] overlayDepth = this.overlayDepth, overlayDepth2 = this.overlayDepth2, light = this.light, source = this.source;
		this.detail = finer;
		this.cells = LodRegion.REGION_BLOCKS >> finer;
		this.allocate(this.size());
		for (int x = 0; x < this.cells; x++) {
			int oldRow = (x >> shift) * oldCells, row = x * this.cells;
			for (int z = 0; z < this.cells; z++) {
				int from = oldRow + (z >> shift), to = row + z;
				this.state[to] = state[from];
				this.height[to] = height[from];
				this.topHeight[to] = topHeight[from];
				this.biome[to] = biome[from];
				this.overlay[to] = overlay[from];
				this.overlayDepth[to] = overlayDepth[from];
				this.overlay2[to] = overlay2[from];
				this.overlayDepth2[to] = overlayDepth2[from];
				this.light[to] = light[from];
				this.source[to] = source[from];
			}
		}
	}

	/**
	 * Merges the part of a section that lies in this region. Cells holding data of a finer section keep it.
	 *
	 * @param now current time in milliseconds
	 * @return the number of cells that changed
	 */
	int merge(MapSection section, long now) {
		int detail = section.detail;
		long sectionMinX = section.originX, sectionMinZ = section.originZ;
		long sectionMaxX = sectionMinX + section.span(), sectionMaxZ = sectionMinZ + section.span();
		int minX = (int)(Math.max(this.minX, sectionMinX)), maxX = (int)(Math.min(this.minX + LodRegion.REGION_BLOCKS, sectionMaxX));
		int minZ = (int)(Math.max(this.minZ, sectionMinZ)), maxZ = (int)(Math.min(this.minZ + LodRegion.REGION_BLOCKS, sectionMaxZ));
		if (minX >= maxX || minZ >= maxZ) return 0;
		int target = Math.min(detail, LodRegion.MAX_DETAIL);
		if (target < this.detail) this.regrid(target);
		int own = this.detail;
		byte sourceValue = (byte)(detail);
		int changed = 0;
		int firstRelX = (minX - section.originX) >> detail, lastRelX = (maxX - 1 - section.originX) >> detail;
		int firstRelZ = (minZ - section.originZ) >> detail, lastRelZ = (maxZ - 1 - section.originZ) >> detail;
		for (int relX = firstRelX; relX <= lastRelX; relX++) {
			int blockMinX = Math.max(minX, section.originX + (relX << detail));
			int blockMaxX = Math.min(maxX, section.originX + ((relX + 1) << detail));
			int firstCellX = (blockMinX - this.minX) >> own, lastCellX = (blockMaxX - 1 - this.minX) >> own;
			for (int relZ = firstRelZ; relZ <= lastRelZ; relZ++) {
				int index = relX * MapSection.WIDTH + relZ;
				BlockState blockState = section.state[index];
				if (blockState == null) continue;
				int blockMinZ = Math.max(minZ, section.originZ + (relZ << detail));
				int blockMaxZ = Math.min(maxZ, section.originZ + ((relZ + 1) << detail));
				int firstCellZ = (blockMinZ - this.minZ) >> own, lastCellZ = (blockMaxZ - 1 - this.minZ) >> own;
				boolean indexed = false;
				short stateIndex = 0, biomeIndex = 0, overlayIndex = 0, overlay2Index = 0, height = 0, topHeight = 0;
				byte overlayDepth = 0, overlayDepth2 = 0, light = 0;
				for (int cellX = firstCellX; cellX <= lastCellX; cellX++) {
					int row = cellX * this.cells;
					for (int cellZ = firstCellZ; cellZ <= lastCellZ; cellZ++) {
						int cell = row + cellZ;
						if (this.source[cell] < detail) continue; // finer data stays
						if (!indexed) {
							indexed = true;
							stateIndex = this.stateIndex(blockState);
							biomeIndex = this.biomeIndex(section.biome[index]);
							BlockState overlay = section.overlay[index], overlay2 = section.overlay2[index];
							overlayIndex = overlay != null ? this.stateIndex(overlay) : -1;
							overlay2Index = overlay != null && overlay2 != null ? this.stateIndex(overlay2) : -1;
							height = (short)(section.height[index]);
							topHeight = (short)(section.topHeight[index]);
							overlayDepth = (byte)(Math.min(section.overlayDepth[index], 255));
							overlayDepth2 = (byte)(Math.min(section.overlayDepth2[index], 255));
							light = section.light[index];
						}
						if (this.state[cell] == stateIndex
							&& this.source[cell] == sourceValue
							&& this.height[cell] == height
							&& this.topHeight[cell] == topHeight
							&& this.biome[cell] == biomeIndex
							&& this.overlay[cell] == overlayIndex
							&& this.overlay2[cell] == overlay2Index
							&& this.overlayDepth[cell] == overlayDepth
							&& this.overlayDepth2[cell] == overlayDepth2
							&& this.light[cell] == light
						) continue;
						this.state[cell] = stateIndex;
						this.height[cell] = height;
						this.topHeight[cell] = topHeight;
						this.biome[cell] = biomeIndex;
						this.overlay[cell] = overlayIndex;
						this.overlayDepth[cell] = overlayDepth;
						this.overlay2[cell] = overlay2Index;
						this.overlayDepth2[cell] = overlayDepth2;
						this.light[cell] = light;
						this.source[cell] = sourceValue;
						changed++;
					}
				}
			}
		}
		if (changed > 0) {
			if (!this.dirty) {
				this.dirty = true;
				this.dirtySince = now;
			}
			this.changedAt = now;
			this.maxSourceKnown = false;
		}
		this.usedAt = now;
		return changed;
	}

	/** The coarsest source over all cells, or {@link LodRegion#NO_SOURCE} while any cell is empty. */
	int maxSource() {
		if (!this.maxSourceKnown) {
			this.maxSource = LodRegion.maxSource(this.source);
			this.maxSourceKnown = true;
		}
		return this.maxSource;
	}

	byte[] coverage() {
		return LodRegion.coverage(this.source, this.detail, this.cells);
	}

	/** An immutable copy with the next revision number; clears {@link #dirty}. */
	@SuppressWarnings("unchecked")
	LodRegion snapshot() {
		this.revision++;
		this.dirty = false;
		return new LodRegion(
			this.regionX, this.regionZ, this.detail, this.revision,
			this.statePalette.toArray(new BlockState[0]),
			this.biomePalette.toArray(new ResourceKey[0]),
			this.state.clone(), this.height.clone(), this.topHeight.clone(), this.biome.clone(), this.overlay.clone(),
			this.overlayDepth.clone(), this.overlay2.clone(), this.overlayDepth2.clone(), this.light.clone(), this.source.clone()
		);
	}
}
