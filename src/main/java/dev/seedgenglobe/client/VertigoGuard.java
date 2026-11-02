package dev.seedgenglobe.client;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;

/**
 * Keeps Vertigo's partial chunks from damaging Distant Horizons LODs in multiplayer.
 * <p>
 * Vertigo only syncs the chunk sections near the player vertically and reports the rest as air. When Distant
 * Horizons turns such a chunk into an LOD, a mountain that rises above the synced range is cut flat, and when the
 * player is underground or high in the air the surface is missing completely. Vertigo tells the client which
 * sections it actually received, so a chunk is only handed to Distant Horizons when, in every column, the synced
 * range contains that column's surface. Rejected chunks are re-offered when Vertigo loads more of their sections.
 */
public final class VertigoGuard {

	/** chunk key -> bit set of loaded section indices (relative to the level's lowest section). */
	static final Map<Long, long[]> LOADED_SECTIONS = new ConcurrentHashMap<>();
	/** chunks that were held back and should be offered again when their sections change. */
	static final Set<Long> HELD_BACK = ConcurrentHashMap.newKeySet();
	static final Set<Long> CHANGED = ConcurrentHashMap.newKeySet();
	static final Map<Long, WeakReference<LevelChunk>> CHUNKS = new ConcurrentHashMap<>();
	static final AtomicLong HELD_COUNT = new AtomicLong(), PASSED_COUNT = new AtomicLong();

	static volatile boolean active;
	static volatile int levelMinSection;
	private static ClientLevel trackedLevel;

	/** Render thread: forget everything when the player changes level, before recording new sections. */
	static void ensureLevel(ClientLevel level) {
		if (level != trackedLevel) {
			trackedLevel = level;
			reset(level != null ? level.getMinY() >> 4 : 0);
		}
	}

	private VertigoGuard() {}

	public static boolean enabled() {
		return ModCompat.VERTIGO && GlobeConfig.get().vertigoGuard;
	}

	static long key(int chunkX, int chunkZ) {
		return ((long)(chunkX) & 0xFFFFFFFFL) | ((long)(chunkZ) << 32);
	}

	// ---------------------------------------------------------------- fed from Vertigo's client events (render thread)

	static void onSectionLoaded(int sectionX, int sectionY, int sectionZ) {
		ensureLevel(Minecraft.getInstance().level);
		long key = key(sectionX, sectionZ);
		int index = sectionY - levelMinSection;
		if (index < 0 || index >= 4096) return;
		LOADED_SECTIONS.compute(key, (k, bits) -> {
			int words = (index >> 6) + 1;
			long[] result = bits == null ? new long[Math.max(words, 2)] : (bits.length < words ? java.util.Arrays.copyOf(bits, words) : bits);
			result[index >> 6] |= 1L << (index & 63);
			return result;
		});
		if (HELD_BACK.contains(key)) CHANGED.add(key);
	}

	static void onSectionUnloaded(int sectionX, int sectionY, int sectionZ) {
		ensureLevel(Minecraft.getInstance().level);
		long key = key(sectionX, sectionZ);
		int index = sectionY - levelMinSection;
		if (index < 0 || index >= 4096) return;
		LOADED_SECTIONS.computeIfPresent(key, (k, bits) -> {
			if ((index >> 6) < bits.length) bits[index >> 6] &= ~(1L << (index & 63));
			for (long word : bits) if (word != 0L) return bits;
			return null;
		});
	}

	static void onChunkLoaded(LevelChunk chunk) {
		ensureLevel(Minecraft.getInstance().level);
		CHUNKS.put(key(chunk.getPos().x(), chunk.getPos().z()), new WeakReference<>(chunk));
	}

	static void onChunkUnloaded(LevelChunk chunk) {
		long key = key(chunk.getPos().x(), chunk.getPos().z());
		CHUNKS.remove(key);
		HELD_BACK.remove(key);
		CHANGED.remove(key);
	}

	static void reset(int minSection) {
		LOADED_SECTIONS.clear();
		HELD_BACK.clear();
		CHANGED.clear();
		CHUNKS.clear();
		levelMinSection = minSection;
	}

	/** Client tick: re-offers held-back chunks whose sections changed since. */
	static void tick(Minecraft minecraft, ClientLevel level) {
		if (CHANGED.isEmpty() || !active || level != trackedLevel) return;
		com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper wrapper;
		try {
			wrapper = DhApi.Delayed.worldProxy != null && DhApi.Delayed.worldProxy.worldLoaded() ? DhApi.Delayed.worldProxy.getSinglePlayerLevel() : null;
		}
		catch (RuntimeException exception) {
			wrapper = null;
		}
		if (wrapper == null || wrapper.getWrappedMcObject() != level) return;
		Long[] keys = CHANGED.toArray(new Long[0]);
		int budget = 64; // spread the work over ticks
		for (Long key : keys) {
			if (budget-- <= 0) break;
			CHANGED.remove(key);
			WeakReference<LevelChunk> reference = CHUNKS.get(key);
			LevelChunk chunk = reference != null ? reference.get() : null;
			if (chunk == null) {
				HELD_BACK.remove(key);
				continue;
			}
			try {
				DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(wrapper, new Object[] { chunk, level });
			}
			catch (Throwable throwable) {
				reofferFailed(throwable);
			}
		}
	}

	private static volatile boolean reofferErrorLogged;

	static void reofferFailed(Throwable throwable) {
		if (!reofferErrorLogged) {
			reofferErrorLogged = true;
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe could not hand a completed Vertigo chunk back to Distant Horizons; it will update when it is next modified.", throwable);
		}
	}

	// ---------------------------------------------------------------- called by the Distant Horizons mixin (DH threads)

	/** @return true when this chunk update must not reach Distant Horizons yet. */
	public static boolean shouldHoldBack(IChunkWrapper chunk, ILevelWrapper level) {
		if (!active || !(level instanceof IClientLevelWrapper)) return false;
		DhChunkPos pos = chunk.getChunkPos();
		long key = key(pos.getX(), pos.getZ());
		long[] bits = LOADED_SECTIONS.get(key);
		boolean hold;
		if (bits == null) {
			hold = true; // nothing synced yet (or this update raced ahead of Vertigo's section events)
		}
		else {
			hold = !surfaceInsideSyncedRange(chunk, bits);
		}
		if (hold) {
			HELD_BACK.add(key);
			HELD_COUNT.incrementAndGet();
		}
		else {
			HELD_BACK.remove(key);
			PASSED_COUNT.incrementAndGet();
		}
		return hold;
	}

	static boolean surfaceInsideSyncedRange(IChunkWrapper chunk, long[] bits) {
		int lowest = -1, highest = -1;
		for (int word = 0; word < bits.length; word++) {
			long value = bits[word];
			if (value == 0L) continue;
			if (lowest < 0) lowest = (word << 6) + Long.numberOfTrailingZeros(value);
			highest = (word << 6) + 63 - Long.numberOfLeadingZeros(value);
		}
		if (lowest < 0) return false;
		int worldBottom = chunk.getInclusiveMinBuildHeight();
		int worldTop = chunk.getExclusiveMaxBuildHeight();
		int rangeBottom = Math.max(((levelMinSection + lowest) << 4), worldBottom);
		int rangeTop = Math.min(((levelMinSection + highest + 1) << 4), worldTop);
		boolean openTop = rangeTop >= worldTop;
		boolean openBottom = rangeBottom <= worldBottom;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				// terrain reaching the top of the synced range continues above it: that column would be cut flat
				if (!openTop && !chunk.getBlockState(x, rangeTop - 1, z).isAir()) return false;
				if (!openBottom) {
					// the column's surface must be inside the range, otherwise DH would store an empty column
					boolean found = false;
					for (int y = rangeTop - 1; y >= rangeBottom; y--) {
						if (!chunk.getBlockState(x, y, z).isAir()) {
							found = true;
							break;
						}
					}
					if (!found) return false;
				}
			}
		}
		return true;
	}

	public static String status() {
		return "Vertigo guard " + (active ? "active" : "inactive") + ": " + PASSED_COUNT.get() + " chunk updates passed, " + HELD_COUNT.get() + " held back, " + HELD_BACK.size() + " waiting for more sections";
	}
}
