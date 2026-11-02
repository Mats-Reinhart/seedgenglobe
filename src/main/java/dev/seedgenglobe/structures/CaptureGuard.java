package dev.seedgenglobe.structures;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Marks the threads that are placing structures into a LOD section.
 * <p>
 * Structure pieces get a stand-in level ({@link CaptureLevel}), but a few things they do still reach the real level:
 * the cat of a swamp hut, for example, asks the real level's structure manager whether it stands in a swamp hut to pick
 * its colour, and in a Big Globe world that question computes structures with Big Globe's own structure locator, in
 * parallel on Java's shared thread pool at normal priority, and fills Big Globe's real structure caches with areas
 * thousands of blocks away. While this guard is up, SeedGenGlobe's mixin answers such questions with "no structure"
 * instead.
 */
public final class CaptureGuard {

	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static volatile boolean noStructureRead;
	private static volatile @Nullable StructureStart noStructure;

	private CaptureGuard() {}

	static void enter() {
		DEPTH.get()[0]++;
	}

	static void exit() {
		int[] depth = DEPTH.get();
		if (depth[0] > 0) depth[0]--;
	}

	/** Whether the current thread is placing structures into a LOD section. */
	public static boolean active() {
		return DEPTH.get()[0] > 0;
	}

	/**
	 * Minecraft's "no structure here" answer ({@code StructureStart.INVALID_START}), read once by name; null on a
	 * Minecraft without it, and then the structure manager is asked as usual.
	 */
	public static @Nullable StructureStart noStructure() {
		if (!noStructureRead) {
			try {
				Object value = StructureStart.class.getField("INVALID_START").get(null);
				noStructure = value instanceof StructureStart start ? start : null;
			}
			catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
				noStructure = null;
			}
			noStructureRead = true;
		}
		return noStructure;
	}
}
