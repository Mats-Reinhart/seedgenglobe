package dev.seedgenglobe.structures;

import dev.seedgenglobe.terrain.SampledColumns;

/**
 * The block columns a LOD section is sampled at: one per LOD cell, 2^detail blocks apart and {@code half} blocks
 * into each cell. Cells are indexed 0 to {@link SampledColumns#WIDTH} - 1 from the section's minimum corner.
 */
final class SampleGrid {

	static final int WIDTH = SampledColumns.WIDTH;

	int originX, originZ, detail, step, half, span;

	void set(int originX, int originZ, int detail, int half) {
		this.originX = originX;
		this.originZ = originZ;
		this.detail = detail;
		this.step = 1 << detail;
		this.half = half;
		this.span = WIDTH << detail;
	}

	/** The cell containing block x, clamped to the section. */
	int cellX(int x) {
		return clamp(Math.floorDiv(x - this.originX, this.step));
	}

	int cellZ(int z) {
		return clamp(Math.floorDiv(z - this.originZ, this.step));
	}

	static int clamp(int cell) {
		return cell < 0 ? 0 : (cell >= WIDTH ? WIDTH - 1 : cell);
	}

	int sampleX(int cell) {
		return this.originX + (cell << this.detail) + this.half;
	}

	int sampleZ(int cell) {
		return this.originZ + (cell << this.detail) + this.half;
	}

	/** Whether block column (x, z) is the sample point of a cell of this section. */
	boolean isSample(int x, int z) {
		int relX = x - this.originX, relZ = z - this.originZ;
		return relX >= 0 && relX < this.span && relZ >= 0 && relZ < this.span
			&& ((relX - this.half) & (this.step - 1)) == 0
			&& ((relZ - this.half) & (this.step - 1)) == 0;
	}

	/** The first cell whose sample point is at or after block x; {@link #WIDTH} when there is none. */
	int firstCellX(int x) {
		return Math.max(0, Math.min(WIDTH, ceilDiv(x - this.originX - this.half, this.step)));
	}

	int firstCellZ(int z) {
		return Math.max(0, Math.min(WIDTH, ceilDiv(z - this.originZ - this.half, this.step)));
	}

	/** The last cell whose sample point is at or before block x; -1 when there is none. */
	int lastCellX(int x) {
		return Math.min(WIDTH - 1, Math.max(-1, Math.floorDiv(x - this.originX - this.half, this.step)));
	}

	int lastCellZ(int z) {
		return Math.min(WIDTH - 1, Math.max(-1, Math.floorDiv(z - this.originZ - this.half, this.step)));
	}

	static int ceilDiv(int value, int divisor) {
		return Math.floorDiv(value + divisor - 1, divisor);
	}
}
