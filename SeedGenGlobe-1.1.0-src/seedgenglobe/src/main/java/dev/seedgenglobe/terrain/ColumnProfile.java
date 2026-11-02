package dev.seedgenglobe.terrain;

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import builderb0y.bigglobe.chunkgen.scripted.BlockSegmentList;
import builderb0y.bigglobe.chunkgen.scripted.BlockSegmentList.LitSegment;
import builderb0y.bigglobe.columns.ScriptedColumn;

/**
 * One terrain column as a list of vertical runs, stored top-down: run 0 is the highest.
 * Runs are contiguous and cover the whole level height, with air filling any gap,
 * which is what Distant Horizons expects for correct light propagation.
 * <p>
 * Instances are reused; nothing here allocates per column once the arrays have grown.
 */
public final class ColumnProfile implements BlockSegmentList.SegmentConsumer {

	public int count;
	public BlockState[] states = new BlockState[16];
	/** inclusive */
	public int[] bottoms = new int[16];
	/** exclusive */
	public int[] tops = new int[16];
	public byte[] skyLight = new byte[16];
	public byte[] blockLight = new byte[16];

	/** Top (exclusive) of the highest non-air run, or the level bottom when the column is empty. */
	public int surfaceTop;
	/** Index of the highest non-air run, or -1. */
	public int surfaceRun;
	public @Nullable Holder<Biome> biome;

	// scratch for expanding one segment, filled bottom-up by accept()
	private int scratchCount;
	private BlockState[] scratchStates = new BlockState[8];
	private int[] scratchMin = new int[8];
	private int[] scratchMax = new int[8];

	public void clear() {
		this.count = 0;
		this.surfaceTop = Integer.MIN_VALUE;
		this.surfaceRun = -1;
		this.biome = null;
	}

	/**
	 * Builds the profile from the segments Big Globe's terrain layer produced for this column.
	 *
	 * @param levelMinY lowest block of the level (inclusive)
	 * @param levelMaxY top of the level (exclusive)
	 * @param topSkyLight 15 in dimensions with a sky, 0 otherwise
	 */
	public void build(ScriptedColumn column, BlockSegmentList segments, int levelMinY, int levelMaxY, byte topSkyLight, BlockState air) {
		this.clear();
		// Same light model as Big Globe's own Distant Horizons output: every segment carries the sky light that reaches
		// its top, and Distant Horizons lights a run's top face with the run's own value.
		segments.computeLightLevels(column, topSkyLight);
		int cursor = levelMaxY;
		LitSegment lowest = null;
		for (int index = segments.size(); --index >= 0;) {
			LitSegment segment = segments.get(index);
			int bottom = Math.max(segment.minY, levelMinY);
			int top = Math.min(segment.maxY + 1, cursor); // segment.maxY is inclusive
			if (top <= bottom) continue;
			byte segmentSky = clampLight(segment.skylightLevel);
			if (top < cursor) {
				// Big Globe does not dim light across gaps, so a gap holds the light that reaches the segment below it.
				// Using the light above the segment over the gap instead would light up cave ceilings and defeat DH's cave culling.
				this.add(air, top, cursor, segmentSky, 0);
			}
			this.scratchCount = 0;
			segment.forEach(column, this); // fills scratch bottom-up
			for (int run = this.scratchCount; --run >= 0;) {
				int runBottom = Math.max(this.scratchMin[run], bottom);
				int runTop = Math.min(this.scratchMax[run] + 1, top);
				if (runTop <= runBottom) continue;
				BlockState state = this.scratchStates[run];
				if (state == null) state = air;
				this.add(state, runBottom, runTop, segmentSky, state.getLightEmission());
			}
			cursor = bottom;
			lowest = segment;
		}
		if (cursor > levelMinY) {
			// below the lowest segment: whatever light gets through it, or open sky for a column with no blocks at all
			byte below = lowest == null ? topSkyLight : clampLight(lowest.getSkyLight(column, lowest.minY, 0));
			this.add(air, levelMinY, cursor, below, 0);
		}
		this.findSurface(levelMinY);
	}

	static byte clampLight(int light) {
		return (byte)(Math.max(0, Math.min(15, light)));
	}

	/** Fills the whole column with air, used when a column could not be computed. */
	public void fillAir(BlockState air, int levelMinY, int levelMaxY, byte topSkyLight) {
		this.clear();
		this.add(air, levelMinY, levelMaxY, topSkyLight, 0);
		this.findSurface(levelMinY);
	}

	@Override
	public void accept(int minY, int maxY, BlockState state) {
		if (this.scratchCount == this.scratchStates.length) {
			int size = this.scratchCount << 1;
			this.scratchStates = Arrays.copyOf(this.scratchStates, size);
			this.scratchMin = Arrays.copyOf(this.scratchMin, size);
			this.scratchMax = Arrays.copyOf(this.scratchMax, size);
		}
		this.scratchStates[this.scratchCount] = state;
		this.scratchMin[this.scratchCount] = minY;
		this.scratchMax[this.scratchCount] = maxY;
		this.scratchCount++;
	}

	/** Appends a run below the current lowest run, merging it into that run when nothing differs. */
	public void add(BlockState state, int bottom, int top, int sky, int light) {
		if (top <= bottom) return;
		int last = this.count - 1;
		if (last >= 0 && this.states[last] == state && this.skyLight[last] == sky && this.blockLight[last] == light && this.bottoms[last] == top) {
			this.bottoms[last] = bottom;
			return;
		}
		this.ensureCapacity(this.count + 1);
		this.states[this.count] = state;
		this.bottoms[this.count] = bottom;
		this.tops[this.count] = top;
		this.skyLight[this.count] = (byte)(sky);
		this.blockLight[this.count] = (byte)(light);
		this.count++;
	}

	public void findSurface(int levelMinY) {
		this.surfaceRun = -1;
		this.surfaceTop = levelMinY;
		for (int run = 0; run < this.count; run++) {
			if (!this.states[run].isAir()) {
				this.surfaceRun = run;
				this.surfaceTop = this.tops[run];
				return;
			}
		}
	}

	/** Index of the run containing y, or -1. */
	public int runAt(int y) {
		for (int run = 0; run < this.count; run++) {
			if (y >= this.bottoms[run] && y < this.tops[run]) return run;
		}
		return -1;
	}

	/**
	 * Replaces [bottom, top) with the given state, splitting existing runs as needed, and updates the surface.
	 * Used to lay tree canopies over the terrain. The range is clipped to the column.
	 */
	public void overlay(BlockState state, int bottom, int top, int sky, int light) {
		this.replaceRange(state, bottom, top, sky, light);
		if (this.count > 0) this.findSurface(this.bottoms[this.count - 1]);
	}

	private void replaceRange(BlockState state, int bottom, int top, int sky, int light) {
		if (top <= bottom || this.count == 0) return;
		bottom = Math.max(bottom, this.bottoms[this.count - 1]);
		top = Math.min(top, this.tops[0]);
		if (top <= bottom) return;
		// rebuild into fresh arrays; columns have few runs so this stays cheap.
		int oldCount = this.count;
		BlockState[] oldStates = Arrays.copyOf(this.states, oldCount);
		int[] oldBottoms = Arrays.copyOf(this.bottoms, oldCount);
		int[] oldTops = Arrays.copyOf(this.tops, oldCount);
		byte[] oldSky = Arrays.copyOf(this.skyLight, oldCount);
		byte[] oldLight = Arrays.copyOf(this.blockLight, oldCount);
		this.count = 0;
		boolean inserted = false;
		for (int run = 0; run < oldCount; run++) {
			int runTop = oldTops[run], runBottom = oldBottoms[run];
			// part above the overlay
			if (runTop > top) {
				this.add(oldStates[run], Math.max(runBottom, top), runTop, oldSky[run], oldLight[run]);
			}
			if (!inserted && runBottom < top && runTop > bottom) {
				this.add(state, bottom, top, sky, light);
				inserted = true;
			}
			// part below the overlay
			if (runBottom < bottom) {
				this.add(oldStates[run], runBottom, Math.min(runTop, bottom), oldSky[run], oldLight[run]);
			}
		}
	}

	private void ensureCapacity(int size) {
		if (size > this.states.length) {
			int newSize = Math.max(size, this.states.length << 1);
			this.states = Arrays.copyOf(this.states, newSize);
			this.bottoms = Arrays.copyOf(this.bottoms, newSize);
			this.tops = Arrays.copyOf(this.tops, newSize);
			this.skyLight = Arrays.copyOf(this.skyLight, newSize);
			this.blockLight = Arrays.copyOf(this.blockLight, newSize);
		}
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder("ColumnProfile[");
		for (int run = 0; run < this.count; run++) {
			if (run > 0) builder.append(", ");
			builder.append(this.bottoms[run]).append("..").append(this.tops[run]).append('=').append(this.states[run]);
		}
		return builder.append(']').toString();
	}
}
