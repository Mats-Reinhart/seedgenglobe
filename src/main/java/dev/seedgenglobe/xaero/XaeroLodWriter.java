package dev.seedgenglobe.xaero;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import xaero.map.MapProcessor;
import xaero.map.MapWriter;
import xaero.map.WorldMapSession;
import xaero.map.file.RegionDetection;
import xaero.map.region.LayeredRegionManager;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapBlock;
import xaero.map.region.MapLayer;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;
import xaero.map.region.Overlay;
import xaero.map.region.OverlayManager;
import xaero.map.world.MapDimension;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * Puts the LOD surface map into Xaero's World Map (and so into the minimap, which draws World Map regions).
 * <p>
 * Runs on the client thread and follows the protocol of Xaero's own {@code MapWriter}: a region is asked to load
 * with "being written" set, tiles are only touched under the region's writer lock while it is loaded and resting,
 * and changed tile chunks are queued for Xaero's own texture update and saving. Only tiles Xaero has not mapped
 * itself are filled (or tiles Xaero recorded as empty, which happens when Vertigo had not synced a chunk's surface).
 * A filled tile carries the detail level of its LOD data in its "world interpretation version" (the coarsest of its
 * cells), so finer LOD data can replace it later, and Xaero maps the real chunk over it as soon as the chunk is loaded
 * (see {@code XaeroLodTileMixin}).
 * <p>
 * The LOD map grows while Distant Horizons generates, so every region is filled again whenever the store saved a new
 * revision of it; only tiles the new data improves are written. Regions Xaero has in memory (the minimap's and the
 * open world map's) get new data within seconds; others are loaded for it only once their data stopped changing for
 * a while, so a region is not loaded over and over while its sections are still coming in.
 * <p>
 * Regions are filled nearest first. Which regions a map already holds is remembered per map:
 * <ul>
 * <li>Multiplayer maps (and singleplayer maps with a custom multiworld) are saved by Xaero, so a region is filled
 * once and kept.</li>
 * <li>Singleplayer maps are rebuilt by Xaero from the world save whenever a region loads, and Xaero keeps only a
 * texture cache of what it showed. A region whose cache was written after it was filled is handed back to Xaero
 * from that cache in later sessions, without filling it again; a region Xaero reloads from the world save gets its
 * LOD tiles back.</li>
 * </ul>
 */
final class XaeroLodWriter {

	static final int SURFACE_LAYER = Integer.MAX_VALUE;
	/** Tiles written from LOD data carry 0xC0 | detail as their world interpretation version. */
	static final int MARKER = 0xC0;
	static final long BUDGET_NANOS = 2_000_000L;
	static final int MAX_OUTSTANDING_LOADS = 3;
	/** Regions loaded on SeedGenGlobe's request that Xaero still holds in memory (about 10 to 20 MB each). */
	static final int MAX_FORCED_IN_MEMORY = 6;
	static final long RESCAN_MILLIS = 30_000L;
	static final long LOAD_TIMEOUT_MILLIS = 60_000L;
	/** A region Xaero does not hold in memory is loaded for new LOD data once that data is this old. */
	static final long SETTLE_MILLIS = 15_000L;

	/** Results of filling or visiting a region. */
	static final int DONE = 0, OUT_OF_TIME = 1, BUSY = 2, WAIT = 3, STOP = 4;

	final LodMapBuilder builder;
	private @Nullable MapProcessor processor;
	private @Nullable Target target;
	private final Map<Long, RegionState> states = new HashMap<>();
	/** Scan position: square rings of regions around the player's region, nearest first. */
	private int scanRegionX = Integer.MIN_VALUE, scanRegionZ = Integer.MIN_VALUE, ring, step;
	private long scanStarted;
	/** Regions the builder just saved, visited ahead of the scan, each once. */
	private final java.util.LinkedHashSet<Long> fresh = new java.util.LinkedHashSet<>();
	private final Map<Long, RegionState> outstanding = new HashMap<>();
	/** Regions written this session that Xaero has not saved and let go of yet. */
	private final List<RegionState> pending = new ArrayList<>();
	private final List<MapRegion> sweepList = new ArrayList<>();
	private int sweepIndex;
	private long lastSweep;
	private boolean reportedNoTerrain;

	long filledTiles, filledRegions, requestedLoads, registeredRegions;

	/** One region in the index of a map: the detail level and revision filled into it, and when (for the texture cache check). */
	record Filled(int detail, long revision, long time) {}

	/** Per dimension and map: where the LOD map comes from and which regions this map already holds. */
	static final class Target {

		static final int INDEX_MAGIC = 0x53474D49, INDEX_VERSION = 2;

		final ResourceKey<Level> dimension;
		final LodMapStore store;
		final MapProcessor processor;
		final String mapIdentity;
		/** Xaero rebuilds this map from the singleplayer world save and keeps only a texture cache of it. */
		final boolean worldSave;
		final int radiusBlocks;
		final Path indexFile;
		final Map<Long, Filled> filled = new HashMap<>();
		boolean indexDirty;
		long indexSavedAt;
		@Nullable Path cacheFolder;
		int cacheFolderVersion = Integer.MIN_VALUE;

		Target(ResourceKey<Level> dimension, LodMapStore store, MapProcessor processor, String mapIdentity, boolean worldSave, int radiusBlocks) {
			this.dimension = dimension;
			this.store = store;
			this.processor = processor;
			this.mapIdentity = mapIdentity;
			this.worldSave = worldSave;
			this.radiusBlocks = radiusBlocks;
			this.indexFile = store.directory.resolve("index-" + Long.toHexString(mapIdentity.hashCode() & 0xFFFFFFFFL) + ".bin");
			this.indexSavedAt = System.currentTimeMillis();
			if (Files.isRegularFile(this.indexFile)) {
				try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(this.indexFile)))) {
					if (in.readInt() == INDEX_MAGIC && in.readInt() == INDEX_VERSION && in.readUTF().equals(mapIdentity)) {
						int count = in.readInt();
						for (int i = 0; i < count; i++) {
							long key = in.readLong();
							int detail = in.readByte();
							long revision = in.readLong();
							long time = in.readLong();
							this.filled.put(key, new Filled(detail, revision, time));
						}
					}
				}
				catch (IOException | RuntimeException exception) {
					this.filled.clear();
				}
			}
		}

		/** Writes the index on the builder thread, at most every 30 seconds unless forced. */
		void saveIndex(LodMapBuilder builder, boolean force) {
			if (!this.indexDirty) return;
			long now = System.currentTimeMillis();
			if (!force && now - this.indexSavedAt < 30_000L) return;
			this.indexSavedAt = now;
			this.indexDirty = false;
			byte[] bytes;
			try {
				ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 + this.filled.size() * 25);
				DataOutputStream out = new DataOutputStream(buffer);
				out.writeInt(INDEX_MAGIC);
				out.writeInt(INDEX_VERSION);
				out.writeUTF(this.mapIdentity);
				out.writeInt(this.filled.size());
				for (Map.Entry<Long, Filled> entry : this.filled.entrySet()) {
					out.writeLong(entry.getKey());
					out.writeByte(entry.getValue().detail());
					out.writeLong(entry.getValue().revision());
					out.writeLong(entry.getValue().time());
				}
				out.flush();
				bytes = buffer.toByteArray();
			}
			catch (IOException impossible) {
				return;
			}
			Path file = this.indexFile;
			builder.runLater(() -> {
				Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
				try {
					Files.write(temporary, bytes);
					Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (IOException | RuntimeException exception) {
					SeedGenGlobe.LOGGER.debug("SeedGenGlobe: could not save the LOD map index {}", file, exception);
				}
			});
		}
	}

	/** What this session did with one region. */
	static final class RegionState {

		final long key;
		final int regionX, regionZ;
		/** The Xaero region being filled or last filled. */
		@Nullable MapRegion region;
		/** Detail level and revision of the fill in progress, its next tile chunk, and whether it wrote anything so far. */
		int fillingDetail = -1, nextTileChunk;
		long fillingRevision = -1L;
		boolean wroteAny;
		/** Detail level and revision put into Xaero's map (or found there) this session, or -1. */
		int filledDetail = -1;
		long filledRevision = -1L;
		/** When the fill was finished, and when it last changed a tile (Xaero must save and cache after that). */
		long filledAt, lastWriteAt;
		long requestedAt, lastRequest;
		/** Loaded on SeedGenGlobe's request: counts against {@link #MAX_FORCED_IN_MEMORY} until Xaero lets go of it. */
		boolean forced;
		boolean inPending, recorded;
		/** Singleplayer map: handed to Xaero to show from its texture cache. */
		boolean registered;
		long skipUntil;

		RegionState(long key) {
			this.key = key;
			this.regionX = LodRegion.keyX(key);
			this.regionZ = LodRegion.keyZ(key);
		}
	}

	XaeroLodWriter(LodMapBuilder builder) {
		this.builder = builder;
	}

	void reset() {
		Target target = this.target;
		if (target != null) target.saveIndex(this.builder, true);
		this.target = null;
		this.processor = null;
		this.states.clear();
		this.outstanding.clear();
		this.pending.clear();
		this.fresh.clear();
		this.sweepList.clear();
		this.sweepIndex = 0;
		this.scanRegionX = Integer.MIN_VALUE;
		this.builder.setTarget(null);
	}

	@Nullable Target target() {
		return this.target;
	}

	void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null) {
			if (this.target != null) this.reset();
			return;
		}
		WorldMapSession session = WorldMapSession.getCurrentSession();
		MapProcessor processor = session != null ? session.getMapProcessor() : null;
		if (processor == null) return;
		if (processor != this.processor) {
			this.reset();
			this.processor = processor;
		}
		if (!processor.getMapSaveLoad().isRegionDetectionComplete() || processor.isWaitingForWorldUpdate()) return;
		if (!processor.isCurrentMultiworldWritable() || processor.isCurrentMapLocked()) return;
		if (processor.getWorld() != level || !level.dimension().equals(processor.getMapWorld().getCurrentDimensionId())) return;
		if (processor.getMapWorld().isCacheOnlyMode()) return;
		MapDimension dimension = processor.getMapWorld().getCurrentDimension();
		if (dimension == null) return;
		Target target = this.ensureTarget(processor, dimension, level);
		if (target == null) return;
		int x = player.getBlockX(), z = player.getBlockZ();
		this.builder.setCenter(x, z);
		long deadline = System.nanoTime() + BUDGET_NANOS;
		synchronized (processor.renderThreadPauseSync) {
			if (processor.isWritingPaused()) return;
			this.work(processor, target, x, z, deadline);
		}
		target.saveIndex(this.builder, false);
	}

	private @Nullable Target ensureTarget(MapProcessor processor, MapDimension dimension, ClientLevel level) {
		Target target = this.target;
		String identity = processor.getCurrentWorldId() + "/" + processor.getCurrentDimId() + "/" + processor.getCurrentMWId();
		if (target != null && target.dimension.equals(level.dimension()) && target.processor == processor && target.mapIdentity.equals(identity)) return target;
		if (target != null) this.reset();
		this.processor = processor;
		LodMapBuilder.Target builderTarget = XaeroLodMap.builderTarget(level, processor);
		if (builderTarget == null) {
			if (!this.reportedNoTerrain) {
				this.reportedNoTerrain = true;
				SeedGenGlobe.LOGGER.info("SeedGenGlobe: no Big Globe terrain for {} on this client yet, so Xaero's map gets no LOD terrain there.", level.dimension().identifier());
			}
			return null;
		}
		this.reportedNoTerrain = false;
		boolean worldSave = dimension.isUsingWorldSave();
		target = new Target(level.dimension(), builderTarget.store(), processor, identity, worldSave, builderTarget.radiusBlocks());
		this.target = target;
		this.builder.setTarget(builderTarget);
		SeedGenGlobe.LOGGER.info(
			"SeedGenGlobe: writing LOD terrain into Xaero's map of {} within {} blocks ({} regions with LOD data, {} already in this map, {}); it follows Distant Horizons' generation as it happens.",
			level.dimension().identifier(),
			target.radiusBlocks,
			target.store.regionCount(),
			target.filled.size(),
			worldSave ? "singleplayer map shown from Xaero's texture cache" : "saved by Xaero"
		);
		return target;
	}

	private RegionState state(long key) {
		return this.states.computeIfAbsent(key, RegionState::new);
	}

	private void work(MapProcessor processor, Target target, int x, int z, long deadline) {
		long now = System.currentTimeMillis();
		this.updateScan(x, z, now);
		this.checkPending(target, now);
		if (!this.workOutstanding(processor, target, deadline, now)) return;
		if (!this.sweep(processor, target, x, z, deadline, now)) return;
		if (!this.workFresh(processor, target, x, z, deadline, now)) return;
		this.scan(processor, target, x, z, deadline, now);
	}

	// ------------------------------------------------------------------------------------------------ scanning

	private void updateScan(int x, int z, long now) {
		int regionX = Math.floorDiv(x, LodRegion.REGION_BLOCKS), regionZ = Math.floorDiv(z, LodRegion.REGION_BLOCKS);
		if (regionX != this.scanRegionX || regionZ != this.scanRegionZ || now - this.scanStarted > RESCAN_MILLIS) {
			this.scanRegionX = regionX;
			this.scanRegionZ = regionZ;
			this.ring = 0;
			this.step = 0;
			this.scanStarted = now;
		}
		Long key;
		while ((key = this.builder.computed.poll()) != null) this.fresh.add(key);
	}

	/** @return false when the time budget ran out */
	private boolean workFresh(MapProcessor processor, Target target, int x, int z, long deadline, long now) {
		while (!this.fresh.isEmpty()) {
			if (System.nanoTime() > deadline) return false;
			Long key = this.fresh.iterator().next();
			int result = this.visit(processor, target, key, x, z, deadline, now);
			if (result == OUT_OF_TIME) return false;
			if (result == WAIT || result == STOP) return true; // come back to it next tick; the scan may go on meanwhile
			this.fresh.remove(key);
		}
		return true;
	}

	private void scan(MapProcessor processor, Target target, int x, int z, long deadline, long now) {
		int lastRing = target.radiusBlocks / LodRegion.REGION_BLOCKS + 2;
		while (this.ring <= lastRing) {
			if (System.nanoTime() > deadline) return;
			long key = LodMapBuilder.ringRegion(this.scanRegionX, this.scanRegionZ, this.ring, this.step);
			int result = this.visit(processor, target, key, x, z, deadline, now);
			if (result == OUT_OF_TIME || result == WAIT || result == STOP) return; // come back to this region next tick
			if (++this.step >= LodMapBuilder.ringSize(this.ring)) {
				this.ring++;
				this.step = 0;
			}
		}
	}

	private int visit(MapProcessor processor, Target target, long key, int x, int z, long deadline, long now) {
		int regionX = LodRegion.keyX(key), regionZ = LodRegion.keyZ(key);
		if (LodMapBuilder.distanceToRegion(x, z, regionX, regionZ) > target.radiusBlocks) return DONE;
		LodMapStore.Meta meta = target.store.meta(regionX, regionZ);
		if (meta == null) return DONE; // nothing yet; the builder's notice brings it back
		if (this.outstanding.containsKey(key)) return DONE;
		RegionState state = this.states.get(key);
		if (state != null) {
			if (state.skipUntil > now) return DONE;
			if (state.filledDetail == meta.detail() && state.filledRevision == meta.revision()) {
				if (!state.registered || this.knownToXaero(processor, regionX, regionZ)) return DONE;
				// Xaero dropped the region it was to show from its cache (the cache was outdated): fill it again
				state.registered = false;
				state.filledDetail = -1;
				state.filledRevision = -1L;
				this.forget(target, key);
			}
		}
		Filled filled = target.filled.get(key);
		if (filled != null && filled.detail() == meta.detail() && filled.revision() == meta.revision()) {
			if (state == null) state = this.state(key);
			if (this.stillInMap(processor, target, regionX, regionZ, filled, state)) {
				state.filledDetail = filled.detail();
				state.filledRevision = filled.revision();
				return DONE;
			}
			this.forget(target, key);
		}
		if (state == null) state = this.state(key);
		MapRegion existing = processor.getLeafMapRegion(SURFACE_LAYER, regionX, regionZ, false);
		if (existing != null && existing.getLoadState() == 2) {
			LodRegion data = this.data(target, regionX, regionZ);
			if (data == null) return WAIT;
			int result = this.fill(processor, target, existing, data, state, deadline, now);
			if (result == BUSY) {
				state.skipUntil = now + 5_000L;
				return DONE;
			}
			return result;
		}
		// sections are still coming in: wait before loading the region, the builder's notice brings it back
		if (meta.changedAt() > 0L && now - meta.changedAt() < SETTLE_MILLIS) return DONE;
		if (this.outstanding.size() >= MAX_OUTSTANDING_LOADS || this.forcedInMemory() >= MAX_FORCED_IN_MEMORY) return STOP;
		MapRegion region = existing != null ? existing : processor.getLeafMapRegion(SURFACE_LAYER, regionX, regionZ, true);
		if (region == null) return DONE;
		if (this.requestLoad(processor, region, state, now)) {
			state.requestedAt = now;
			state.forced = true;
			this.outstanding.put(key, state);
			this.requestedLoads++;
		}
		else {
			state.skipUntil = now + 2_000L; // Xaero is loading or caching it right now
		}
		return DONE;
	}

	/** Whether Xaero has the region, loaded or not: a region object, or a region file (or world save region) it found. */
	private boolean knownToXaero(MapProcessor processor, int regionX, int regionZ) {
		return processor.getLeafMapRegion(SURFACE_LAYER, regionX, regionZ, false) != null || processor.regionDetectionExists(SURFACE_LAYER, regionX, regionZ);
	}

	/** Whether the LOD tiles filled into this region in an earlier session are still part of the map. */
	private boolean stillInMap(MapProcessor processor, Target target, int regionX, int regionZ, Filled filled, RegionState state) {
		if (!target.worldSave) return this.knownToXaero(processor, regionX, regionZ); // Xaero saved the region with them
		// singleplayer: only Xaero's texture cache has them, and only if it was written after they were
		long cached = this.cacheTime(processor, target, regionX, regionZ);
		if (cached < 0 || cached < filled.time()) return false;
		if (!this.knownToXaero(processor, regionX, regionZ)) {
			// a region the world save does not have: tell Xaero it exists, so it shows its cached textures
			MapLayer layer = processor.getMapWorld().getCurrentDimension().getLayeredMapRegions().getLayer(SURFACE_LAYER);
			layer.addRegionDetection(new RegionDetection(
				processor.getCurrentWorldId(), processor.getCurrentDimId(), processor.getCurrentMWId(),
				regionX, regionZ, null, processor.getGlobalVersion(), true
			));
			state.registered = true;
			this.registeredRegions++;
		}
		return true;
	}

	/** Modification time of Xaero's texture cache of a region, or -1 when there is none. */
	private long cacheTime(MapProcessor processor, Target target, int regionX, int regionZ) {
		int version = processor.getGlobalVersion();
		if (target.cacheFolderVersion != version) {
			target.cacheFolderVersion = version;
			target.cacheFolder = null;
			try {
				RegionDetection probe = new RegionDetection(processor.getCurrentWorldId(), processor.getCurrentDimId(), processor.getCurrentMWId(), 0, 0, null, version, true);
				File file = processor.getMapSaveLoad().getCacheFile(probe, SURFACE_LAYER, false, false);
				if (file != null) target.cacheFolder = file.toPath().getParent();
			}
			catch (IOException | RuntimeException exception) {
				SeedGenGlobe.LOGGER.debug("SeedGenGlobe: could not find Xaero's cache folder", exception);
			}
		}
		Path folder = target.cacheFolder;
		if (folder == null) return -1L;
		try {
			return Files.getLastModifiedTime(folder.resolve(regionX + "_" + regionZ + ".xwmc")).toMillis();
		}
		catch (IOException | RuntimeException exception) {
			return -1L;
		}
	}

	private void remember(Target target, long key, int detail, long revision, long time) {
		target.filled.put(key, new Filled(detail, revision, time));
		target.indexDirty = true;
	}

	private void forget(Target target, long key) {
		if (target.filled.remove(key) != null) target.indexDirty = true;
	}

	/** The region's current data in memory, or null after asking the builder to read it. */
	private @Nullable LodRegion data(Target target, int regionX, int regionZ) {
		LodMapStore.Meta meta = target.store.meta(regionX, regionZ);
		if (meta == null) return null;
		LodRegion data = target.store.cached(regionX, regionZ);
		if (data != null && data.revision == meta.revision()) return data;
		this.builder.requestLoad(regionX, regionZ);
		return null;
	}

	private boolean requestLoad(MapProcessor processor, MapRegion region, RegionState state, long now) {
		synchronized (region) {
			if (region.getLoadState() == 2 || !region.isResting() || !region.canRequestReload_unsynced()) return false;
			region.setBeingWritten(true);
			processor.getMapSaveLoad().requestLoad(region, "SeedGenGlobe LOD map", false);
		}
		state.lastRequest = now;
		return true;
	}

	private int forcedInMemory() {
		int count = this.outstanding.size();
		for (RegionState state : this.pending) {
			if (state.forced) count++;
		}
		return count;
	}

	// ------------------------------------------------------------------------------------------------ loaded regions

	/** @return false when the time budget ran out */
	private boolean workOutstanding(MapProcessor processor, Target target, long deadline, long now) {
		for (Iterator<RegionState> iterator = this.outstanding.values().iterator(); iterator.hasNext(); ) {
			RegionState state = iterator.next();
			MapRegion region = processor.getLeafMapRegion(SURFACE_LAYER, state.regionX, state.regionZ, false);
			if (region == null || now - state.requestedAt > LOAD_TIMEOUT_MILLIS) {
				// gone (Xaero drops a region it cannot load) or stuck: the next scan starts over with it
				iterator.remove();
				state.forced = false;
				continue;
			}
			if (region.getLoadState() != 2) {
				// Xaero loads a region it holds no map data for in two steps, the first from its texture cache only,
				// and drops a request that arrives while its loader is busy: ask again whenever it can take one
				if (now - state.lastRequest > 250L) this.requestLoad(processor, region, state, now);
				continue;
			}
			LodRegion data = this.data(target, state.regionX, state.regionZ);
			if (data == null) {
				if (target.store.meta(state.regionX, state.regionZ) == null) {
					iterator.remove();
					state.forced = false;
				}
				continue;
			}
			if (System.nanoTime() > deadline) return false;
			int result = this.fill(processor, target, region, data, state, deadline, now);
			if (result == OUT_OF_TIME) return false;
			if (result == DONE) {
				iterator.remove();
				if (!state.inPending) state.forced = false;
			}
			// BUSY: Xaero is saving or caching it; try again next tick
		}
		return true;
	}

	/**
	 * Loaded regions within the radius that have room for LOD tiles: regions Xaero (re)built from a singleplayer
	 * world save, regions with unexplored chunks next to explored ones, and regions holding coarser LOD tiles.
	 *
	 * @return false when the time budget ran out
	 */
	private boolean sweep(MapProcessor processor, Target target, int x, int z, long deadline, long now) {
		if (this.sweepIndex >= this.sweepList.size()) {
			if (now - this.lastSweep < 2_000L) return true;
			this.lastSweep = now;
			this.sweepList.clear();
			this.sweepIndex = 0;
			LayeredRegionManager regions = processor.getMapWorld().getCurrentDimension().getLayeredMapRegions();
			try {
				int count = regions.loadedCount();
				for (int i = 0; i < count; i++) {
					LeveledRegion<?> leveled = regions.getLoadedRegion(i);
					if (leveled instanceof MapRegion region
						&& region.getCaveLayer() == SURFACE_LAYER
						&& region.getLoadState() == 2
						&& LodMapBuilder.distanceToRegion(x, z, region.getRegionX(), region.getRegionZ()) <= target.radiusBlocks
					) this.sweepList.add(region);
				}
			}
			catch (RuntimeException concurrentChange) {
				this.sweepList.clear(); // Xaero's loading thread changed the list; try again next time
			}
		}
		while (this.sweepIndex < this.sweepList.size()) {
			if (System.nanoTime() > deadline) return false;
			MapRegion region = this.sweepList.get(this.sweepIndex);
			int result = this.sweepRegion(processor, target, region, deadline, now);
			if (result == OUT_OF_TIME) return false;
			if (result == WAIT) return true; // its data is being read; the scan may go on meanwhile
			this.sweepIndex++;
		}
		return true;
	}

	private int sweepRegion(MapProcessor processor, Target target, MapRegion region, long deadline, long now) {
		if (region.getLoadState() != 2) return DONE;
		int regionX = region.getRegionX(), regionZ = region.getRegionZ();
		LodMapStore.Meta meta = target.store.meta(regionX, regionZ);
		if (meta == null) return DONE;
		int stored = meta.detail();
		long key = LodRegion.key(regionX, regionZ);
		if (this.outstanding.containsKey(key)) return DONE;
		RegionState state = this.state(key);
		if (state.skipUntil > now) return DONE;
		boolean midway = state.region == region && state.fillingDetail == stored && state.fillingRevision == meta.revision() && state.nextTileChunk < 64;
		if (!midway && !anyWritable(region, stored)) {
			// every tile is Xaero's own or as fine as the data gets
			state.filledDetail = stored;
			state.filledRevision = meta.revision();
			return DONE;
		}
		LodRegion data = this.data(target, regionX, regionZ);
		if (data == null) return target.store.meta(regionX, regionZ) == null ? DONE : WAIT;
		int result = this.fill(processor, target, region, data, state, deadline, now);
		if (result == BUSY) {
			state.skipUntil = now + 5_000L;
			return DONE;
		}
		if (result == DONE && !state.wroteAny) state.skipUntil = now + 30_000L; // room, but no LOD data for it
		return result;
	}

	/** Whether any tile of a loaded region could take LOD data of this detail. */
	static boolean anyWritable(MapRegion region, int detail) {
		synchronized (region) {
			for (int lx = 0; lx < 8; lx++) {
				for (int lz = 0; lz < 8; lz++) {
					MapTileChunk chunk = region.getChunk(lx, lz);
					if (chunk == null) return true;
					if (chunk.getLoadState() != 2) continue;
					for (int ix = 0; ix < 4; ix++) {
						for (int iz = 0; iz < 4; iz++) {
							if (writable(chunk.getTile(ix, iz), detail)) return true;
						}
					}
				}
			}
		}
		return false;
	}

	/** Records regions once Xaero saved them, and stops counting forced regions once Xaero let go of them. */
	private void checkPending(Target target, long now) {
		for (Iterator<RegionState> iterator = this.pending.iterator(); iterator.hasNext(); ) {
			RegionState state = iterator.next();
			MapRegion region = state.region;
			boolean inMemory = region != null && region.getLoadState() == 2;
			boolean saved = !inMemory || region.getLastSaveTime() >= state.lastWriteAt;
			boolean release = !inMemory || now - state.filledAt > 120_000L;
			if ((saved || release) && !state.recorded && state.filledDetail >= 0) {
				// a singleplayer map's index entry is checked against the time Xaero's cache was written, so a
				// region recorded before Xaero cached it is simply filled again next session
				state.recorded = true;
				this.remember(target, state.key, state.filledDetail, state.filledRevision, state.lastWriteAt);
			}
			if (release) {
				iterator.remove();
				state.inPending = false;
				state.forced = false;
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ writing

	/**
	 * Fills the region's tiles from LOD data, continuing where an earlier call stopped.
	 *
	 * @return DONE, OUT_OF_TIME, or BUSY when Xaero is saving or caching the region
	 */
	private int fill(MapProcessor processor, Target target, MapRegion region, LodRegion data, RegionState state, long deadline, long now) {
		if (state.region != region || state.fillingDetail != data.detail || state.fillingRevision != data.revision || state.nextTileChunk >= 64) {
			state.region = region;
			state.fillingDetail = data.detail;
			state.fillingRevision = data.revision;
			state.nextTileChunk = 0;
			state.wroteAny = false;
			state.recorded = false;
		}
		boolean wroteNow = false;
		int result = DONE;
		while (state.nextTileChunk < 64) {
			if (System.nanoTime() > deadline) {
				result = OUT_OF_TIME;
				break;
			}
			int written = this.fillTileChunk(processor, region, data, state.nextTileChunk >> 3, state.nextTileChunk & 7);
			if (written < 0) {
				result = BUSY;
				break;
			}
			if (written > 0) wroteNow = true;
			state.nextTileChunk++;
		}
		if (wroteNow) {
			state.wroteAny = true;
			state.lastWriteAt = System.currentTimeMillis();
			this.afterWrite(processor, region);
		}
		if (result != DONE) return result;
		state.filledDetail = data.detail;
		state.filledRevision = data.revision;
		state.filledAt = now;
		this.filledRegions++;
		if (state.wroteAny) {
			if (!state.inPending) {
				state.inPending = true;
				this.pending.add(state);
			}
		}
		else {
			// nothing to add: what is there is complete, as far as the index is concerned
			Filled previous = target.filled.get(state.key);
			this.remember(target, state.key, data.detail, data.revision, previous != null ? previous.time() : 0L);
			state.recorded = true;
		}
		return DONE;
	}

	/** Asks Xaero to save the region soon, the way its world save reader does after building a region. */
	private void afterWrite(MapProcessor processor, MapRegion region) {
		synchronized (region) {
			region.setLastSaveTime(System.currentTimeMillis() - processor.getSaveTime() + 1500L);
		}
	}

	/** @return tiles written, or -1 when Xaero is busy with the region */
	private int fillTileChunk(MapProcessor processor, MapRegion region, LodRegion data, int lx, int lz) {
		int tileChunkX = (region.getRegionX() << 3) + lx;
		int tileChunkZ = (region.getRegionZ() << 3) + lz;
		synchronized (region.writerThreadPauseSync) {
			if (region.isWritingPaused()) return -1;
			MapTileChunk tileChunk;
			boolean created = false;
			synchronized (region) {
				if (region.getLoadState() != 2 || !region.isResting()) return -1;
				tileChunk = region.getChunk(lx, lz);
				if (tileChunk == null) {
					if (!hasData(data, lx, lz)) return 0;
					region.setBeingWritten(true);
					tileChunk = new MapTileChunk(region, tileChunkX, tileChunkZ);
					region.setChunk(lx, lz, tileChunk);
					tileChunk.setLoadState((byte)(2));
					region.setAllCachePrepared(false);
					created = true;
				}
			}
			if (tileChunk.getLoadState() != 2) return 0;
			if (tileChunk.getLeafTexture().shouldDownloadFromPBO()) return -1; // Xaero is reading the texture back for its cache
			MapWriter writer = processor.getMapWriter();
			OverlayManager overlays = processor.getOverlayManager();
			int worldBottom = processor.getWorld().getMinY();
			int caveDepth = processor.getCaveModeDepthConfig();
			String dimension = processor.getCurrentDimension();
			int written = 0;
			for (int ix = 0; ix < 4; ix++) {
				for (int iz = 0; iz < 4; iz++) {
					MapTile tile = tileChunk.getTile(ix, iz);
					int localX = ((lx << 2) + ix) << 4, localZ = ((lz << 2) + iz) << 4;
					// a tile is written whole or not at all, and carries the coarsest detail among its cells
					int source = data.areaSource(localX, localZ, 16);
					if (source >= LodRegion.NO_SOURCE || !writable(tile, source)) continue;
					if (!created && written == 0) {
						synchronized (region) {
							region.setBeingWritten(true);
						}
					}
					if (tile == null) tile = processor.getTilePool().get(dimension, (tileChunkX << 2) + ix, (tileChunkZ << 2) + iz);
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							tile.setBlock(x, z, block(tile.getBlock(x, z), data, data.cell(localX + x, localZ + z), writer, overlays, worldBottom));
						}
					}
					tile.setWorldInterpretationVersion(MARKER | Math.min(source, 15));
					tile.setWrittenCave(Integer.MAX_VALUE, caveDepth);
					tileChunk.setTile(ix, iz, tile, processor.getBlockStateShortShapeCache(), processor);
					tile.setWrittenOnce(true);
					tile.setLoaded(true);
					written++;
				}
			}
			if (created) {
				if (tileChunk.includeInSave()) tileChunk.setHasHadTerrain();
				processor.getMapRegionHighlightsPreparer().prepare(region, lx, lz, false);
				if (!tileChunk.includeInSave() && !tileChunk.hasHighlightsIfUndiscovered()) {
					synchronized (region) {
						region.setChunk(lx, lz, null);
					}
					return written;
				}
			}
			if (written > 0) {
				tileChunk.setToUpdateBuffers(true);
				touchNeighbours(region, lx, lz);
				this.filledTiles += written;
				if (!region.isNormalMapData()) {
					// as Xaero's writer does: other cave layers of a world save map must be rebuilt
					region.getDim().getLayeredMapRegions().applyToEachLoadedLayer((layer, mapLayer) -> {
						if (layer != region.getCaveLayer()) {
							MapRegion other = processor.getLeafMapRegion(layer, region.getRegionX(), region.getRegionZ(), true);
							if (other != null) {
								other.setOutdatedWithOtherLayers(true);
								other.setHasHadTerrain();
							}
						}
					});
				}
			}
			return written;
		}
	}

	/**
	 * Xaero shades a pixel by the heights of the pixels above and above-left of it, so the first row and column of
	 * the tile chunks below and to the right of a changed one need their shading redone.
	 */
	static void touchNeighbours(MapRegion region, int lx, int lz) {
		MapTileChunk right = lx < 7 ? region.getChunk(lx + 1, lz) : null;
		MapTileChunk below = lz < 7 ? region.getChunk(lx, lz + 1) : null;
		MapTileChunk diagonal = lx < 7 && lz < 7 ? region.getChunk(lx + 1, lz + 1) : null;
		if (right != null && right.getLoadState() == 2) {
			for (int iz = 0; iz < 4; iz++) {
				MapTile tile = right.getTile(0, iz);
				if (tile == null || !tile.isLoaded()) continue;
				for (int z = 0; z < 16; z++) unknownSlope(tile, 0, z);
			}
			right.setToUpdateBuffers(true);
		}
		if (below != null && below.getLoadState() == 2) {
			for (int ix = 0; ix < 4; ix++) {
				MapTile tile = below.getTile(ix, 0);
				if (tile == null || !tile.isLoaded()) continue;
				for (int x = 0; x < 16; x++) unknownSlope(tile, x, 0);
			}
			below.setToUpdateBuffers(true);
		}
		if (diagonal != null && diagonal.getLoadState() == 2) {
			MapTile tile = diagonal.getTile(0, 0);
			if (tile != null && tile.isLoaded()) {
				unknownSlope(tile, 0, 0);
				diagonal.setToUpdateBuffers(true);
			}
		}
	}

	private static void unknownSlope(MapTile tile, int x, int z) {
		MapBlock block = tile.getBlock(x, z);
		if (block != null) block.setSlopeUnknown(true);
	}

	/** May LOD data of this detail go into this tile? */
	static boolean writable(@Nullable MapTile tile, int detail) {
		if (tile == null || !tile.isLoaded()) return true;
		int version = tile.getWorldInterpretationVersion();
		if ((version & 0xF0) == MARKER) return (version & 0x0F) > detail;
		return isVoid(tile);
	}

	/** A tile Xaero mapped from a chunk that had no blocks: Vertigo had not synced its surface. */
	static boolean isVoid(MapTile tile) {
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				MapBlock block = tile.getBlock(x, z);
				if (block == null) continue;
				BlockState state = block.getState();
				if (state != null && !state.isAir()) return false;
				if (block.getNumberOfOverlays() != 0) return false;
			}
		}
		return true;
	}

	/** Whether any tile of the tile chunk has every cell known. */
	static boolean hasData(LodRegion data, int lx, int lz) {
		for (int ix = 0; ix < 4; ix++) {
			for (int iz = 0; iz < 4; iz++) {
				if (data.areaSource(((lx << 2) + ix) << 4, ((lz << 2) + iz) << 4, 16) < LodRegion.NO_SOURCE) return true;
			}
		}
		return false;
	}

	/** One map pixel the way Xaero's writer builds it, reusing the pixel object a pooled tile still holds. */
	static MapBlock block(@Nullable MapBlock reuse, LodRegion data, int cell, MapWriter writer, OverlayManager overlays, int worldBottom) {
		MapBlock block = reuse != null ? reuse : new MapBlock();
		block.prepareForWriting(worldBottom);
		byte light = data.light[cell];
		BlockState overlay = data.overlayAt(cell);
		if (overlay != null) {
			block.addOverlay(overlay(overlay, data.overlayDepth[cell] & 0xFF, light, writer, overlays));
			BlockState overlay2 = data.overlay2At(cell);
			if (overlay2 != null) block.addOverlay(overlay(overlay2, data.overlayDepth2[cell] & 0xFF, light, writer, overlays));
		}
		BlockState state = data.stateAt(cell);
		ResourceKey<Biome> biome = data.biomeAt(cell);
		block.write(state, data.height[cell], data.topHeight[cell], biome, light, writer.isGlowing(state), false);
		return block;
	}

	/** Xaero's overlay of a see-through block this deep: each block adds its light dampening to the opacity, up to 15. */
	private static Overlay overlay(BlockState state, int depth, byte light, MapWriter writer, OverlayManager overlays) {
		Overlay overlay = new Overlay(state, light, writer.isGlowing(state));
		overlay.increaseOpacity(Math.min(15, state.getLightDampening() * depth));
		return overlays.getOriginal(overlay);
	}

	List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		Target target = this.target;
		if (target == null) {
			lines.add("Xaero LOD map: idle (no Big Globe dimension mapped by Xaero right now)");
			return lines;
		}
		lines.add(String.format(
			Locale.ROOT,
			"Xaero LOD map: radius %d blocks, %d regions with LOD data, %d of them filled into this map (%s)",
			target.radiusBlocks, target.store.regionCount(), target.filled.size(),
			target.worldSave ? "singleplayer map, shown from Xaero's texture cache" : "saved by Xaero"
		));
		lines.add(String.format(
			Locale.ROOT,
			"Xaero LOD map this session: %d regions filled (%d tiles), %d shown from cache, %d loads requested, %d loading, %d waiting for Xaero to save",
			this.filledRegions, this.filledTiles, this.registeredRegions, this.requestedLoads, this.outstanding.size(), this.pending.size()
		));
		return lines;
	}
}
