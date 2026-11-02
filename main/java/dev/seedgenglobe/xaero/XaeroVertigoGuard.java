package dev.seedgenglobe.xaero;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.chunk.LevelChunk;

import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.client.VertigoGuard;

/**
 * Decides whether Xaero's map writers may read a client chunk while Vertigo syncs sections individually.
 * Unlike the Distant Horizons guard this applies in singleplayer too: Xaero always maps the client's chunks.
 * Called on the render thread, where Vertigo's API may be used.
 */
public final class XaeroVertigoGuard {

	/** Set every client tick: Vertigo installed, the guard enabled and the player tracking sections. */
	public static volatile boolean active;
	static final AtomicLong HIDDEN = new AtomicLong(), PASSED = new AtomicLong();
	private static volatile boolean failed;

	private XaeroVertigoGuard() {}

	/** @return true when the chunk's surface is not (completely) synced yet, so Xaero must not map it now */
	public static boolean hidden(LevelChunk chunk) {
		if (!active || failed) return false;
		try {
			boolean hidden = !VertigoGuard.surfaceSynced(chunk);
			(hidden ? HIDDEN : PASSED).incrementAndGet();
			return hidden;
		}
		catch (Throwable throwable) {
			if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
			failed = true;
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: could not check Vertigo's synced sections for Xaero's map; the check is off for this session.", throwable);
			return false;
		}
	}

	public static String status() {
		return "Xaero map Vertigo check " + (active && !failed ? "active" : "inactive") + ": " + PASSED.get() + " chunks mapped, " + HIDDEN.get() + " skipped until their surface is synced";
	}
}
