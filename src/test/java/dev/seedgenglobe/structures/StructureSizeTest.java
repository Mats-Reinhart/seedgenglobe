package dev.seedgenglobe.structures;

/**
 * Checks the detail levels small structure sets stop at (see {@link StructureIndex#detailForSpan}): a structure is
 * kept while LOD cells are at most half as wide as it is.
 */
public final class StructureSizeTest {

	static int checks;

	static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) {
		int[][] cases = {
			{ 0, 0 }, { 1, 0 }, { 3, 0 }, { 4, 1 }, { 8, 2 }, { 10, 2 }, { 15, 2 }, { 16, 3 }, { 20, 3 }, { 31, 3 },
			{ 32, 4 }, { 63, 4 }, { 64, 5 }, { 128, 6 }, { 200, 6 }, { 256, 7 }
		};
		for (int[] example : cases) {
			int detail = StructureIndex.detailForSpan(example[0]);
			check(detail == example[1], example[0] + " blocks wide: detail " + detail + ", expected " + example[1]);
		}
		for (int span = 1; span < 5000; span++) {
			int detail = StructureIndex.detailForSpan(span);
			check(span >= 2 << detail || detail == 0, span + " blocks covers at least two cells at detail " + detail);
			check(span < 2 << (detail + 1), span + " blocks is less than two cells at detail " + (detail + 1));
			check(detail >= StructureIndex.detailForSpan(span - 1), "wider structures show at least as far");
		}
		System.out.println("StructureSizeTest: " + checks + " checks passed");
	}
}
