package dev.seedgenglobe.structures;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.block.state.BlockState;

import dev.seedgenglobe.terrain.ColumnProfile;
import dev.seedgenglobe.terrain.SampledColumns;

/**
 * The world structures are placed into while one LOD section is generated: its terrain columns, plus the blocks
 * structures placed so far.
 * <p>
 * A section only has one terrain column per LOD cell, sampled at the cell's sample point. Reads anywhere in a cell
 * see that column, and only blocks placed exactly at a sample point are kept, since that is the column the LOD shows.
 * At detail 0 every block column is a sample point, so structures come out block for block.
 * <p>
 * Not thread safe: each sampler has its own.
 */
final class CaptureView {

	static final int WIDTH = SampledColumns.WIDTH;
	static final int COLUMNS = WIDTH * WIDTH;
	private static final int POOL_LIMIT = 8192;

	final SampledColumns columns;
	final BlockState air;
	final SampleGrid grid = new SampleGrid();
	int levelMinY, levelMaxY;
	private int sectionCount;

	/** Placed blocks: [column][16-block section of the column][y within the section] */
	private final BlockState[][][] writes = new BlockState[COLUMNS][][];
	private final boolean[] touched = new boolean[COLUMNS];
	private final int[] touchedList = new int[COLUMNS];
	private int touchedCount;
	private final int[] minWriteY = new int[COLUMNS], maxWriteY = new int[COLUMNS];
	private final ArrayDeque<BlockState[]> pool = new ArrayDeque<>();

	// scratch for apply()
	private BlockState[] runStates = new BlockState[16];
	private int[] runBottoms = new int[16], runTops = new int[16];

	CaptureView(SampledColumns columns) {
		this.columns = columns;
		this.air = columns.air();
	}

	void begin(int originX, int originZ, int detail, int levelMinY, int levelMaxY) {
		this.grid.set(originX, originZ, detail, this.columns.halfCell(detail));
		this.levelMinY = levelMinY;
		this.levelMaxY = levelMaxY;
		int sections = (levelMaxY - levelMinY + 15) >> 4;
		if (sections != this.sectionCount) {
			this.end();
			Arrays.fill(this.writes, null);
			this.sectionCount = sections;
		}
	}

	/** Index of the LOD column containing block column (x, z): cell x * WIDTH + cell z, like the sampler's profiles. */
	int column(int x, int z) {
		return this.grid.cellX(x) * WIDTH + this.grid.cellZ(z);
	}

	ColumnProfile profile(int column) {
		return this.columns.profile(column / WIDTH, column % WIDTH);
	}

	BlockState state(int x, int y, int z) {
		if (y < this.levelMinY || y >= this.levelMaxY) return this.air;
		int column = this.column(x, z);
		BlockState written = this.written(column, y);
		if (written != null) return written;
		BlockState terrain = this.profile(column).stateAt(y);
		return terrain != null ? terrain : this.air;
	}

	void write(int x, int y, int z, @Nullable BlockState state) {
		if (state == null || y < this.levelMinY || y >= this.levelMaxY || !this.grid.isSample(x, z)) return;
		this.put(this.column(x, z), y, state);
	}

	private @Nullable BlockState written(int column, int y) {
		BlockState[][] sections = this.writes[column];
		if (sections == null) return null;
		BlockState[] section = sections[(y - this.levelMinY) >> 4];
		return section != null ? section[(y - this.levelMinY) & 15] : null;
	}

	private void put(int column, int y, BlockState state) {
		BlockState[][] sections = this.writes[column];
		if (sections == null) sections = this.writes[column] = new BlockState[this.sectionCount][];
		int index = (y - this.levelMinY) >> 4;
		BlockState[] section = sections[index];
		if (section == null) {
			section = this.pool.poll();
			if (section == null) section = new BlockState[16];
			sections[index] = section;
		}
		section[(y - this.levelMinY) & 15] = state;
		if (!this.touched[column]) {
			this.touched[column] = true;
			this.touchedList[this.touchedCount++] = column;
			this.minWriteY[column] = y;
			this.maxWriteY[column] = y;
		}
		else {
			if (y < this.minWriteY[column]) this.minWriteY[column] = y;
			if (y > this.maxWriteY[column]) this.maxWriteY[column] = y;
		}
	}

	boolean anyWrites() {
		return this.touchedCount > 0;
	}

	/**
	 * The heightmap value of a column: one above the highest block the heightmap counts, or the bottom of the level.
	 */
	int height(Predicate<BlockState> counts, int x, int z) {
		int column = this.column(x, z);
		ColumnProfile profile = this.profile(column);
		int top = this.terrainHeight(profile, counts, this.levelMaxY);
		if (!this.touched[column]) return top;
		for (int y = this.maxWriteY[column]; y >= top; y--) {
			BlockState written = this.written(column, y);
			if (written != null && counts.test(written)) return y + 1;
		}
		// placed blocks may have replaced the top of the terrain
		for (int y = top - 1; y >= this.levelMinY; y--) {
			BlockState state = this.written(column, y);
			if (state == null) {
				if (y < this.minWriteY[column]) return this.terrainHeight(profile, counts, y + 1);
				state = profile.stateAt(y);
			}
			if (state != null && counts.test(state)) return y + 1;
		}
		return this.levelMinY;
	}

	/** One above the highest counted terrain block below {@code limit}. */
	private int terrainHeight(ColumnProfile profile, Predicate<BlockState> counts, int limit) {
		for (int run = 0; run < profile.count; run++) {
			if (profile.bottoms[run] >= limit) continue;
			if (counts.test(profile.states[run])) return Math.min(profile.tops[run], limit);
		}
		return this.levelMinY;
	}

	/**
	 * Puts the placed blocks into the section's columns and relights them.
	 *
	 * @param occupied set for every column where blocks went on or above the terrain's top block,
	 * so tree canopies and ground cover leave them alone
	 */
	void apply(int topSkyLight, boolean[] occupied) {
		for (int index = 0; index < this.touchedCount; index++) {
			int column = this.touchedList[index];
			int runs = 0;
			for (int y = this.maxWriteY[column]; y >= this.minWriteY[column]; y--) {
				BlockState state = this.written(column, y);
				if (state == null) continue;
				if (runs > 0 && this.runStates[runs - 1] == state && this.runBottoms[runs - 1] == y + 1) {
					this.runBottoms[runs - 1] = y;
					continue;
				}
				if (runs == this.runStates.length) {
					int size = runs << 1;
					this.runStates = Arrays.copyOf(this.runStates, size);
					this.runBottoms = Arrays.copyOf(this.runBottoms, size);
					this.runTops = Arrays.copyOf(this.runTops, size);
				}
				this.runStates[runs] = state;
				this.runTops[runs] = y + 1;
				this.runBottoms[runs] = y;
				runs++;
			}
			ColumnProfile profile = this.profile(column);
			int terrainTop = profile.surfaceTop;
			profile.replaceRuns(runs, this.runStates, this.runBottoms, this.runTops);
			profile.relight(topSkyLight);
			profile.findSurface(this.levelMinY);
			if (this.maxWriteY[column] >= terrainTop - 1) occupied[column] = true;
			Arrays.fill(this.runStates, 0, runs, null);
		}
	}

	/** Forgets all placed blocks. */
	void end() {
		for (int index = 0; index < this.touchedCount; index++) {
			int column = this.touchedList[index];
			BlockState[][] sections = this.writes[column];
			if (sections != null) {
				for (int section = 0; section < sections.length; section++) {
					BlockState[] states = sections[section];
					if (states == null) continue;
					sections[section] = null;
					if (this.pool.size() < POOL_LIMIT) {
						Arrays.fill(states, null);
						this.pool.push(states);
					}
				}
			}
			this.touched[column] = false;
		}
		this.touchedCount = 0;
	}
}
