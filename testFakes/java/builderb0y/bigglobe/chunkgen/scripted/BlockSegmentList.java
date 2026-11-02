package builderb0y.bigglobe.chunkgen.scripted;

import java.util.ArrayList;
import java.util.function.IntFunction;

import net.minecraft.world.level.block.state.BlockState;

import builderb0y.bigglobe.columns.ScriptedColumn;

/**
 * Test double for Big Globe's BlockSegmentList, written for SeedGenGlobe's tests (no Big Globe code is included).
 * It behaves the way SeedGenGlobe expects the real class to behave:
 * <ul>
 * <li>segments are kept bottom-up and their minY/maxY are inclusive;</li>
 * <li>computeLightLevels gives each segment the sky light that reaches its top, and the light passing through
 * a segment drops by the opacity of its top block times its height;</li>
 * <li>forEach reports maximal runs of equal states, lowest run first;</li>
 * <li>getSkyLight dims the segment's light by the opacity of the block at y for every block above y.</li>
 * </ul>
 */
public class BlockSegmentList extends ArrayList<BlockSegmentList.LitSegment> {

	public final int minY, maxY;

	public BlockSegmentList(int minY, int maxY) {
		this.minY = minY;
		this.maxY = maxY - 1; // stored inclusive
	}

	/** One state from minY to maxY, both inclusive. Segments must be added bottom-up without overlapping. */
	public BlockSegmentList fixed(int minY, int maxYInclusive, BlockState state) {
		this.add(new LitSegment(minY, maxYInclusive, true, y -> state));
		return this;
	}

	/** A state per y, like a scripted segment. */
	public BlockSegmentList dynamic(int minY, int maxYInclusive, IntFunction<BlockState> states) {
		this.add(new LitSegment(minY, maxYInclusive, false, states));
		return this;
	}

	public void computeLightLevels(ScriptedColumn column, byte topLightLevel) {
		int light = topLightLevel;
		for (int index = this.size() - 1; index >= 0; index--) {
			LitSegment segment = this.get(index);
			segment.skylightLevel = (byte)(light);
			int height = segment.maxY - segment.minY + 1;
			light = Math.max(0, light - segment.getBlockState(column, segment.maxY).opacity * height);
		}
	}

	public static interface SegmentConsumer {

		public abstract void accept(int minY, int maxY, BlockState state);
	}

	public static class LitSegment {

		public int minY, maxY;
		public byte skylightLevel = -1;
		final boolean single;
		final IntFunction<BlockState> states;

		LitSegment(int minY, int maxY, boolean single, IntFunction<BlockState> states) {
			this.minY = minY;
			this.maxY = maxY;
			this.single = single;
			this.states = states;
		}

		public void forEach(ScriptedColumn column, SegmentConsumer action) {
			if (this.single) {
				action.accept(this.minY, this.maxY, this.states.apply(this.minY));
				return;
			}
			int start = this.minY;
			BlockState current = this.getBlockState(column, start);
			for (int y = this.minY + 1; y <= this.maxY + 1; y++) {
				BlockState state = y <= this.maxY ? this.getBlockState(column, y) : null; // null closes the last run
				if (state != current) {
					action.accept(start, y - 1, current);
					start = y;
					current = state;
				}
			}
		}

		public int getSkyLight(ScriptedColumn column, int y, int lod) {
			int dimming = (this.maxY - y) * this.getBlockState(column, y).opacity << lod;
			return Math.max(0, Math.min(15, this.skylightLevel - dimming));
		}

		public BlockState getBlockState(ScriptedColumn column, int y) {
			return this.states.apply(y);
		}

		public int minY() {
			return this.minY;
		}

		public int maxY() {
			return this.maxY + 1;
		}
	}
}
