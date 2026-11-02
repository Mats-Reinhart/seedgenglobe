package dev.seedgenglobe.xaero;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.dh.GlobeWorldGenerator;
import dev.seedgenglobe.gen.GenProgress;
import dev.seedgenglobe.gen.SectionScheduler;
import dev.seedgenglobe.structures.LodStructures;
import dev.seedgenglobe.terrain.CanopyModel;
import dev.seedgenglobe.terrain.GlobeTerrain;
import dev.seedgenglobe.terrain.GroundCover;
import dev.seedgenglobe.terrain.SampledColumns;

/**
 * Background thread that keeps the LOD surface map in {@link LodMapStore} filled.
 * <p>
 * The map is drawn from the same sections Distant Horizons gets: every section SeedGenGlobe generates for it is read
 * the way Xaero reads a chunk column (on the generating thread, see {@link MapFeed}) and merged here into the regions
 * it covers, keeping the finest data each cell has seen. Regions that changed are saved after a few seconds and handed
 * to the {@link XaeroLodWriter map writer}, so the map grows with the LODs, in the same detail levels.
 * <p>
 * What Distant Horizons does not generate (areas it already had, a Distant Horizons server's LODs, and the finer
 * detail the map wants near the player) the map computes itself, as jobs on the shared {@link SectionScheduler}, which
 * runs them nearest to the player first, between Distant Horizons' own sections. Up to 1.2.0 this thread computed one
 * section at a time at the lowest priority and was starved by generation, so gaps next to the player stayed black
 * while generation moved outward. Now, nearest first:
 * <ul>
 * <li>the 64-block squares next to chunks Xaero has mapped itself get one pixel per block, so explored land never
 * surrounds a coarse patch ({@link #requestExplored});</li>
 * <li>within the full detail radius of the player everything gets one pixel per block;</li>
 * <li>gaps (cells with no data at all) are filled anywhere within the map radius, far ones first at a coarser detail
 * level, which is quick, and refined later;</li>
 * <li>areas coarser than Distant Horizons would show them are refined, but only behind the area Distant Horizons is
 * generating, since sections it generates reach the map anyway.</li>
 * </ul>
 * Also reads stored regions from disk for the map writer, so the render thread never touches the disk.
 */
public final class LodMapBuilder implements Runnable {

	/** Everything the builder needs for one dimension. */
	public record Target(
		LodMapStore store,
		@Nullable GlobeWorldGenerator generator,
		GlobeTerrain terrain,
		@Nullable CanopyModel.Shared canopy,
		@Nullable GroundCover.Shared ground,
		@Nullable LodStructures structures,
		int levelMinY,
		int levelMaxY,
		byte topSkyLight,
		int radiusBlocks,
		XaeroPixel.Rules rules
	) {}

	/** Coarsest detail level the builder computes: one map pixel per 64 blocks. */
	static final int MAX_DETAIL = 6;
	/** Gaps nearer than this (plus the full detail radius) are filled at the detail wanted there right away. */
	static final int NEAR_FIELD_BLOCKS = 2048;
	/** Farther gaps are first filled this many detail levels coarser than wanted. */
	static final int COARSE_FILL_LEVELS = 2;
	/** The scan starts over from the nearest region once the player moved this far. */
	static final int RESCAN_DISTANCE = 128;
	/** A region that received data is saved and handed to the writer this long after its first change. */
	static final long PUBLISH_MILLIS = 3_000L;
	/** Regions that received nothing for this long are saved if needed and let go. */
	static final long IDLE_MILLIS = 20_000L;
	/** Cells all regions being merged may hold together, about 16 bytes each: the memory load preset's amount. */
	static long canvasCells() {
		return GlobeConfig.get().memoryLoad.mapCells();
	}

	/** Cells the store keeps in memory for the writer. */
	static long storeCacheCells() {
		return GlobeConfig.get().memoryLoad.mapCells();
	}
	/** Sections from Distant Horizons waiting to be merged; beyond this they are dropped, and the builder fills the gap later. */
	static final int MAX_INCOMING = 256;
	/** How far behind the nearest section Distant Horizons generated lately the builder stays. */
	static final double FRONT_MARGIN = 768.0D;
	/** Distant Horizons counts as generating while its last section is at most this old. */
	static final long FRONT_MILLIS = 20_000L;

	/** One region's summary of how fine its data is, for the revision it was computed from. */
	private record Coverage(long revision, byte[] squares) {}

	/** A section the builder wants computed. */
	private record Want(long regionKey, int originX, int originZ, int detail, SectionScheduler.Kind kind) {}

	private volatile @Nullable Target target;
	private volatile @Nullable MapFeed feed;
	private volatile int centerX, centerZ;
	private volatile boolean running = true;
	private final ConcurrentLinkedQueue<Long> loadRequests = new ConcurrentLinkedQueue<>();
	private final Set<Long> requested = ConcurrentHashMap.newKeySet();
	/** Regions that were just saved with new data, for the writer. */
	final ConcurrentLinkedQueue<Long> computed = new ConcurrentLinkedQueue<>();
	/** Small jobs (file writes) handed over from the render thread. */
	private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<MapSection> incoming = new ConcurrentLinkedQueue<>();
	private final AtomicInteger incomingCount = new AtomicInteger();
	/** Sections the map's own jobs computed, merged ahead of the ones from Distant Horizons. */
	private final ConcurrentLinkedQueue<MapSection> built = new ConcurrentLinkedQueue<>();
	/** Map jobs on the scheduler, by {@link #sectionKey}. */
	private final Set<Long> outstanding = ConcurrentHashMap.newKeySet();
	/** 64-block squares next to explored chunks that should get one pixel per block (block x, z of the square). */
	private final ConcurrentLinkedQueue<long[]> explored = new ConcurrentLinkedQueue<>();
	private final Set<Long> exploredSeen = ConcurrentHashMap.newKeySet();
	private final Thread thread;
	/** Progress of the map's own sections, for the log, the chat and the overlay (one per target). */
	private volatile @Nullable GenProgress progress;

	// builder thread state
	private @Nullable LodMapStore canvasStore;
	private final List<Want> exploredWants = new ArrayList<>();
	private final Map<Long, RegionCanvas> canvases = new HashMap<>();
	private final Map<Long, Coverage> coverage = new HashMap<>();
	private final Set<Long> failed = ConcurrentHashMap.newKeySet();
	private int scanCenterX = Integer.MIN_VALUE, scanCenterZ = Integer.MIN_VALUE, cleanRings;
	private double frontDistance = Double.POSITIVE_INFINITY;
	private long frontAt, lastLiveAt;
	/** When the map's own sections were last planned, and when a full scan that found nothing may run again. */
	private long scheduledAt, quietUntil;
	private int quietCenterX, quietCenterZ;

	final AtomicLong liveSections = new AtomicLong(), droppedSections = new AtomicLong();
	final AtomicLong builtSections = new AtomicLong(), builtNanos = new AtomicLong(), savedRegions = new AtomicLong();

	/** Merging and saving are cheap and must keep up with generation, so the thread runs just below normal priority. */
	static final int MERGE_PRIORITY = Thread.NORM_PRIORITY - 1;
	/** Sections merged in a row before saved regions get a turn. */
	static final int MERGES_PER_PUBLISH = 16;

	private int mergedInARow;

	public LodMapBuilder() {
		this.thread = new Thread(this, "SeedGenGlobe LOD Map");
		this.thread.setDaemon(true);
		this.thread.setPriority(MERGE_PRIORITY);
		this.thread.start();
	}

	public void setTarget(@Nullable Target target) {
		Target previous = this.target;
		if (previous == target) return;
		MapFeed oldFeed = this.feed;
		if (previous != null && previous.generator() != null && oldFeed != null && previous.generator().sectionListener() == oldFeed) {
			previous.generator().setSectionListener(null);
		}
		if (previous != null) SectionScheduler.get().cancel(previous);
		GenProgress oldProgress = this.progress;
		if (oldProgress != null) oldProgress.close();
		this.progress = target != null && target.generator() != null
			? new GenProgress("Xaero LOD map", target.generator().dimensionId, target, true, target::radiusBlocks)
			: null;
		this.target = target;
		MapFeed feed = target != null && target.generator() != null ? new MapFeed(this, target) : null;
		this.feed = feed;
		if (feed != null) target.generator().setSectionListener(feed);
		this.loadRequests.clear();
		this.requested.clear();
		this.computed.clear();
		this.explored.clear();
		this.exploredSeen.clear();
		LockSupport.unpark(this.thread);
	}

	/** Progress of the map's own sections, or null without a target. */
	public @Nullable GenProgress progress() {
		return this.progress;
	}

	public @Nullable Target target() {
		return this.target;
	}

	public void setCenter(int x, int z) {
		this.centerX = x;
		this.centerZ = z;
	}

	/** Asks for a stored region to be read into the store's memory cache. */
	public void requestLoad(int regionX, int regionZ) {
		long key = LodRegion.key(regionX, regionZ);
		if (this.requested.add(key)) {
			this.loadRequests.add(key);
			LockSupport.unpark(this.thread);
		}
	}

	/**
	 * Asks for one pixel per block in the 64-block square containing (x, z): Xaero mapped real chunks next to it, and
	 * a coarse LOD patch surrounded by explored land looks wrong (any thread; each square is asked about once).
	 */
	public void requestExplored(int x, int z) {
		int minX = Math.floorDiv(x, 64) * 64, minZ = Math.floorDiv(z, 64) * 64;
		if (this.exploredSeen.add(sectionKey(minX, minZ, 0))) {
			this.explored.add(new long[] { minX, minZ });
			LockSupport.unpark(this.thread);
		}
	}

	/** Runs a small job on the builder thread, ahead of any terrain work. */
	public void runLater(Runnable task) {
		this.tasks.add(task);
		LockSupport.unpark(this.thread);
	}

	public void shutdown() {
		this.running = false;
		LockSupport.unpark(this.thread);
	}

	/** Whether a section generated for Distant Horizons is worth reading for the map (any thread). */
	boolean wants(Target target, int originX, int originZ, int detail) {
		if (this.target != target) return false;
		if (this.incomingCount.get() >= MAX_INCOMING) {
			this.droppedSections.incrementAndGet();
			return false;
		}
		int span = SampledColumns.WIDTH << Math.min(detail, 20);
		double dx = this.centerX < originX ? originX - this.centerX : Math.max(0, this.centerX - (originX + span - 1));
		double dz = this.centerZ < originZ ? originZ - this.centerZ : Math.max(0, this.centerZ - (originZ + span - 1));
		return Math.sqrt(dx * dx + dz * dz) <= target.radiusBlocks();
	}

	/** Hands a section generated for Distant Horizons to the builder thread (any thread). */
	void offer(MapSection section) {
		this.incomingCount.incrementAndGet();
		this.incoming.add(section);
		LockSupport.unpark(this.thread);
	}

	int incomingCount() {
		return this.incomingCount.get();
	}

	// ------------------------------------------------------------------------------------------------ geometry

	/**
	 * The detail level Distant Horizons shows a section with at this distance from the camera:
	 * {@code log(distance / (16 * unit)) / log(base)} for its horizontal quality, no finer than its maximum
	 * horizontal resolution and here no coarser than {@link #MAX_DETAIL}.
	 */
	static int dhDetail(double distance, double unitBlocks, double logBase, int finest) {
		int detail = distance <= 0.0D ? 0 : (int)(Math.log(distance / unitBlocks) / logBase);
		return Math.max(finest, Math.min(detail, MAX_DETAIL));
	}

	static double distanceToRegion(int x, int z, int regionX, int regionZ) {
		int minX = regionX * LodRegion.REGION_BLOCKS, minZ = regionZ * LodRegion.REGION_BLOCKS;
		double dx = x < minX ? minX - x : Math.max(0, x - (minX + LodRegion.REGION_BLOCKS - 1));
		double dz = z < minZ ? minZ - z : Math.max(0, z - (minZ + LodRegion.REGION_BLOCKS - 1));
		return Math.sqrt(dx * dx + dz * dz);
	}

	static double distanceToSquare(int x, int z, long minX, long minZ, long span) {
		double dx = x < minX ? minX - x : Math.max(0, x - (minX + span - 1));
		double dz = z < minZ ? minZ - z : Math.max(0, z - (minZ + span - 1));
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Number of regions on ring {@code ring} (the square of regions {@code ring} steps away from the center). */
	static int ringSize(int ring) {
		return ring == 0 ? 1 : ring * 8;
	}

	/** The {@code step}th region of a ring around (centerX, centerZ): top row, bottom row, then both sides. */
	static long ringRegion(int centerX, int centerZ, int ring, int step) {
		int regionX, regionZ;
		if (ring == 0) {
			regionX = centerX;
			regionZ = centerZ;
		}
		else if (step < 2 * ring + 1) { // top row
			regionX = centerX - ring + step;
			regionZ = centerZ - ring;
		}
		else if (step < 4 * ring + 2) { // bottom row
			regionX = centerX - ring + (step - (2 * ring + 1));
			regionZ = centerZ + ring;
		}
		else if (step < 6 * ring + 1) { // left column without corners
			regionX = centerX - ring;
			regionZ = centerZ - ring + 1 + (step - (4 * ring + 2));
		}
		else { // right column without corners
			regionX = centerX + ring;
			regionZ = centerZ - ring + 1 + (step - (6 * ring + 1));
		}
		return LodRegion.key(regionX, regionZ);
	}

	/** Distant Horizons' detail scheme: how far a detail level reaches, and the finest one it uses. */
	record Scheme(double unitBlocks, double logBase, int finest, int fullDetailBlocks) {

		static final Scheme MEDIUM = new Scheme(12 * 16, Math.log(2.0D), 0, 0);

		Scheme(double unitBlocks, double logBase, int finest) {
			this(unitBlocks, logBase, finest, 0);
		}

		/** The detail level wanted at this distance: one pixel per block within the full detail radius. */
		int wantedAt(double distance) {
			return distance <= this.fullDetailBlocks ? 0 : this.detailAt(distance);
		}

		static Scheme current() {
			try {
				if (DhApi.Delayed.configs == null) return MEDIUM;
				EDhApiHorizontalQuality quality = DhApi.Delayed.configs.graphics().horizontalQuality().getValue();
				EDhApiMaxHorizontalResolution resolution = DhApi.Delayed.configs.graphics().maxHorizontalResolution().getValue();
				double unit = quality != null ? quality.distanceUnitInBlocks * 16.0D : MEDIUM.unitBlocks;
				double base = quality != null && quality.quadraticBase > 1.0D ? quality.quadraticBase : 2.0D;
				int finest = resolution != null ? Math.max(0, Math.min(resolution.detailLevel, MAX_DETAIL)) : 0;
				return new Scheme(unit, Math.log(base), finest);
			}
			catch (RuntimeException | LinkageError error) {
				return MEDIUM;
			}
		}

		int detailAt(double distance) {
			return dhDetail(distance, this.unitBlocks, this.logBase, this.finest);
		}

		/** The scheme with one pixel per block within {@code blocks} of the player. */
		Scheme withFullDetail(int blocks) {
			return new Scheme(this.unitBlocks, this.logBase, this.finest, blocks);
		}
	}

	/**
	 * The sections Distant Horizons would show over a region, seen from (x, z): a quadtree walk from the coarsest
	 * section down to the detail each section's center asks for, keeping the sections that overlap the region.
	 *
	 * @param out filled with {originX, originZ, detail} triples
	 */
	static void sectionsOver(int regionX, int regionZ, int x, int z, Scheme scheme, List<int[]> out) {
		int top = SampledColumns.WIDTH << MAX_DETAIL;
		int minX = regionX * LodRegion.REGION_BLOCKS, minZ = regionZ * LodRegion.REGION_BLOCKS;
		collect(Math.floorDiv(minX, top) * top, Math.floorDiv(minZ, top) * top, MAX_DETAIL, minX, minZ, x, z, scheme, out);
	}

	private static void collect(int originX, int originZ, int detail, int regionMinX, int regionMinZ, int x, int z, Scheme scheme, List<int[]> out) {
		int span = SampledColumns.WIDTH << detail;
		if (originX >= regionMinX + LodRegion.REGION_BLOCKS || originX + span <= regionMinX) return;
		if (originZ >= regionMinZ + LodRegion.REGION_BLOCKS || originZ + span <= regionMinZ) return;
		// full detail where any part of the section is in the radius; DH's scheme by the section's centre elsewhere
		double nearest = distanceToSquare(x, z, originX, originZ, span);
		double cx = originX + span / 2.0D - x, cz = originZ + span / 2.0D - z;
		int wanted = nearest <= scheme.fullDetailBlocks() ? 0 : scheme.detailAt(Math.sqrt(cx * cx + cz * cz));
		if (detail > wanted && detail > 0) {
			int half = span >> 1;
			collect(originX, originZ, detail - 1, regionMinX, regionMinZ, x, z, scheme, out);
			collect(originX + half, originZ, detail - 1, regionMinX, regionMinZ, x, z, scheme, out);
			collect(originX, originZ + half, detail - 1, regionMinX, regionMinZ, x, z, scheme, out);
			collect(originX + half, originZ + half, detail - 1, regionMinX, regionMinZ, x, z, scheme, out);
			return;
		}
		out.add(new int[] { originX, originZ, detail });
	}

	// ------------------------------------------------------------------------------------------------ main loop

	@Override
	public void run() {
		while (this.running) {
			try {
				Runnable task = this.tasks.poll();
				if (task != null) {
					task.run();
					continue;
				}
				Target target = this.target;
				if (target == null || target.store() != this.canvasStore) {
					this.releaseCanvases();
					this.canvasStore = target != null ? target.store() : null;
					this.coverage.clear();
					this.cleanRings = 0;
					this.frontDistance = Double.POSITIVE_INFINITY;
					this.failed.clear();
					this.exploredWants.clear();
					this.built.clear();
				}
				if (target == null) {
					this.dropIncoming();
					LockSupport.parkNanos(1_000_000_000L);
					continue;
				}
				Long load = this.loadRequests.poll();
				if (load != null) {
					target.store().load(LodRegion.keyX(load), LodRegion.keyZ(load));
					this.requested.remove(load);
					continue;
				}
				long now = System.currentTimeMillis();
				MapSection mine = this.built.poll();
				if (mine != null) {
					if (mine.owner == target) this.merge(target, mine, now);
					continue;
				}
				if (this.mergedInARow < MERGES_PER_PUBLISH) {
					MapSection section = this.incoming.poll();
					if (section != null) {
						this.incomingCount.decrementAndGet();
						this.mergedInARow++;
						if (section.owner == target) this.merge(target, section, now);
						continue;
					}
				}
				this.mergedInARow = 0;
				if (this.publishDue(target, now)) continue;
				// the map's own sections are planned at least twice a second, even while Distant Horizons' keep coming
				if (this.incoming.isEmpty() || now - this.scheduledAt >= 500L) {
					this.scheduledAt = now;
					this.schedule(target, now);
				}
				if (!this.incoming.isEmpty()) continue;
				LockSupport.parkNanos(250_000_000L);
			}
			catch (Throwable throwable) {
				if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: the LOD map builder hit an error; it pauses for a few seconds.", throwable);
				LockSupport.parkNanos(5_000_000_000L);
			}
		}
		this.releaseCanvases();
	}

	private void dropIncoming() {
		while (this.incoming.poll() != null) this.incomingCount.decrementAndGet();
	}

	// ------------------------------------------------------------------------------------------------ merging

	/** Merges a section into every region of the map it overlaps. */
	private void merge(Target target, MapSection section, long now) {
		int span = section.span();
		int firstRegionX = Math.floorDiv(section.originX, LodRegion.REGION_BLOCKS);
		int firstRegionZ = Math.floorDiv(section.originZ, LodRegion.REGION_BLOCKS);
		int lastRegionX = Math.floorDiv(section.originX + span - 1, LodRegion.REGION_BLOCKS);
		int lastRegionZ = Math.floorDiv(section.originZ + span - 1, LodRegion.REGION_BLOCKS);
		int x = this.centerX, z = this.centerZ;
		int radius = target.radiusBlocks();
		for (int regionX = firstRegionX; regionX <= lastRegionX; regionX++) {
			for (int regionZ = firstRegionZ; regionZ <= lastRegionZ; regionZ++) {
				if (distanceToRegion(x, z, regionX, regionZ) > radius) continue;
				long key = LodRegion.key(regionX, regionZ);
				RegionCanvas canvas = this.canvases.get(key);
				if (canvas == null) {
					LodMapStore.Meta meta = target.store().meta(regionX, regionZ);
					// every cell already holds data at least this fine: nothing to add
					if (meta != null && meta.maxSource() <= section.detail) continue;
					canvas = this.openCanvas(target, regionX, regionZ, section.detail);
				}
				canvas.merge(section, now);
			}
		}
		if (section.live) {
			this.liveSections.incrementAndGet();
			double distance = distanceToSquare(x, z, section.originX, section.originZ, span);
			if (now - this.frontAt > FRONT_MILLIS || distance < this.frontDistance) {
				this.frontDistance = distance;
				this.frontAt = now;
			}
			this.lastLiveAt = now;
		}
		this.trimCanvases(target, now);
	}

	private RegionCanvas openCanvas(Target target, int regionX, int regionZ, int detail) {
		LodRegion stored = target.store().meta(regionX, regionZ) != null ? target.store().load(regionX, regionZ) : null;
		RegionCanvas canvas = stored != null ? RegionCanvas.of(stored) : RegionCanvas.empty(regionX, regionZ, detail);
		canvas.usedAt = System.currentTimeMillis();
		this.canvases.put(LodRegion.key(regionX, regionZ), canvas);
		return canvas;
	}

	/** Lets go of the least recently used regions while they hold more cells than allowed. */
	private void trimCanvases(Target target, long now) {
		long cells = 0L;
		for (RegionCanvas canvas : this.canvases.values()) cells += canvas.size();
		while (cells > canvasCells() && this.canvases.size() > 1) {
			RegionCanvas eldest = null;
			for (RegionCanvas canvas : this.canvases.values()) {
				if (eldest == null || canvas.usedAt < eldest.usedAt) eldest = canvas;
			}
			if (eldest == null) return;
			if (eldest.dirty) this.publish(target.store(), eldest);
			this.canvases.remove(LodRegion.key(eldest.regionX, eldest.regionZ));
			cells -= eldest.size();
		}
	}

	/** Saves one region that is due, or lets go of one that is idle. @return whether anything was done */
	private boolean publishDue(Target target, long now) {
		for (Iterator<RegionCanvas> iterator = this.canvases.values().iterator(); iterator.hasNext(); ) {
			RegionCanvas canvas = iterator.next();
			if (canvas.dirty && now - canvas.dirtySince >= PUBLISH_MILLIS) {
				this.publish(target.store(), canvas);
				return true;
			}
			if (now - canvas.usedAt >= IDLE_MILLIS) {
				if (canvas.dirty) this.publish(target.store(), canvas);
				else this.computed.add(LodRegion.key(canvas.regionX, canvas.regionZ)); // settled: the writer may load it now
				iterator.remove();
				return true;
			}
		}
		return false;
	}

	private void publish(LodMapStore store, RegionCanvas canvas) {
		LodRegion region = canvas.snapshot();
		long key = LodRegion.key(region.regionX, region.regionZ);
		if (store.save(region, canvas.changedAt)) {
			this.coverage.put(key, new Coverage(region.revision, region.coverage()));
			this.savedRegions.incrementAndGet();
			this.computed.add(key);
		}
		else {
			canvas.dirty = true; // try again later
			canvas.dirtySince = System.currentTimeMillis();
		}
	}

	/** Saves every region that changed and forgets them all (target change or shutdown). */
	private void releaseCanvases() {
		LodMapStore store = this.canvasStore;
		if (store != null) {
			for (RegionCanvas canvas : this.canvases.values()) {
				if (canvas.dirty) this.publish(store, canvas);
			}
		}
		this.canvases.clear();
	}

	// ------------------------------------------------------------------------------------------------ the map's own sections

	static long sectionKey(int originX, int originZ, int detail) {
		return (((long)(originX >> 6)) & 0xFFFFFFL) | ((((long)(originZ >> 6)) & 0xFFFFFFL) << 24) | ((long)(detail & 0xFF) << 48);
	}

	/** How far out refinement may go: everywhere, or only behind the area Distant Horizons is generating. */
	private double reach(Target target, long now) {
		if (now - this.lastLiveAt > FRONT_MILLIS) return target.radiusBlocks();
		return Math.min(target.radiusBlocks(), this.frontDistance - FRONT_MARGIN);
	}

	/** One pixel per block within this many blocks of the player. */
	static int fullDetailBlocks() {
		return GlobeConfig.get().mapFullDetailRadius * 16;
	}

	/**
	 * Map sections that may be on the scheduler at once: enough to keep every thread busy while Distant Horizons is
	 * quiet, and half of them while it generates (its sections reach the map anyway).
	 */
	private int jobLimit(GlobeWorldGenerator generator) {
		int threads = Math.max(1, SectionScheduler.get().threads());
		return generator.progress.busy() ? Math.max(1, (threads + 1) / 2) : threads + 1;
	}

	/** Puts the map sections wanted most on the scheduler, up to the limit. */
	private void schedule(Target target, long now) {
		GlobeWorldGenerator generator = target.generator();
		if (generator == null || generator.isClosed()) return;
		int room = this.jobLimit(generator) - this.outstanding.size();
		if (room <= 0) return;
		this.takeExplored(target);
		List<Want> wants = new ArrayList<>();
		this.exploredWants.sort(java.util.Comparator.comparingDouble(want -> distanceToSquare(this.centerX, this.centerZ, want.originX(), want.originZ(), 64)));
		for (java.util.Iterator<Want> iterator = this.exploredWants.iterator(); iterator.hasNext() && wants.size() < room; ) {
			Want want = iterator.next();
			iterator.remove();
			if (this.coarserThan(target, want.originX(), want.originZ(), 64, 0)) wants.add(want);
		}
		if (wants.size() < room && (now >= this.quietUntil || Math.abs(this.centerX - this.quietCenterX) > RESCAN_DISTANCE || Math.abs(this.centerZ - this.quietCenterZ) > RESCAN_DISTANCE)) {
			int before = wants.size();
			this.findWants(target, now, room - wants.size(), wants);
			if (wants.size() == before) {
				// nothing to do anywhere: scan again in a while, or as soon as the player moves
				this.quietUntil = now + 2_000L;
				this.quietCenterX = this.centerX;
				this.quietCenterZ = this.centerZ;
			}
		}
		for (Want want : wants) this.submit(target, want);
	}

	/** Turns squares next to explored chunks into wants (detail 0), skipping ones already that fine. */
	private void takeExplored(Target target) {
		long[] square;
		int radius = target.radiusBlocks();
		while ((square = this.explored.poll()) != null) {
			int minX = (int)(square[0]), minZ = (int)(square[1]);
			if (distanceToSquare(this.centerX, this.centerZ, minX, minZ, 64) > radius) continue;
			if (this.exploredWants.size() >= 4096) break;
			long regionKey = LodRegion.key(Math.floorDiv(minX, LodRegion.REGION_BLOCKS), Math.floorDiv(minZ, LodRegion.REGION_BLOCKS));
			this.exploredWants.add(new Want(regionKey, minX, minZ, 0, SectionScheduler.Kind.MAP_EXPLORED));
		}
	}

	/** Whether the map's data over a square is coarser than {@code detail} (or missing somewhere). */
	private boolean coarserThan(Target target, int minX, int minZ, int span, int detail) {
		int regionX = Math.floorDiv(minX, LodRegion.REGION_BLOCKS), regionZ = Math.floorDiv(minZ, LodRegion.REGION_BLOCKS);
		byte[] squares = this.squaresOf(target, regionX, regionZ);
		if (squares == null) return true;
		int localX = minX - regionX * LodRegion.REGION_BLOCKS, localZ = minZ - regionZ * LodRegion.REGION_BLOCKS;
		return LodRegion.coverageSource(squares, localX, localZ, localX + span, localZ + span) > detail;
	}

	/** The coverage summary of a region from its canvas or the store, or null when it has no data at all. */
	private byte @Nullable [] squaresOf(Target target, int regionX, int regionZ) {
		RegionCanvas canvas = this.canvases.get(LodRegion.key(regionX, regionZ));
		if (canvas != null) return canvas.coverage();
		LodMapStore.Meta meta = target.store().meta(regionX, regionZ);
		return meta == null ? null : this.coverageOf(target, regionX, regionZ, meta);
	}

	/** Collects up to {@code max} wanted sections, scanning regions in rings around the player, nearest first. */
	private void findWants(Target target, long now, int max, List<Want> out) {
		int x = this.centerX, z = this.centerZ;
		if (Math.abs(x - this.scanCenterX) > RESCAN_DISTANCE || Math.abs(z - this.scanCenterZ) > RESCAN_DISTANCE) {
			// the player moved: wanted detail levels changed, so rings that were complete may want finer data now
			this.scanCenterX = x;
			this.scanCenterZ = z;
			this.cleanRings = 0;
		}
		Scheme scheme = Scheme.current().withFullDetail(fullDetailBlocks());
		double reach = this.reach(target, now);
		GlobeWorldGenerator generator = target.generator();
		int centerRegionX = Math.floorDiv(x, LodRegion.REGION_BLOCKS), centerRegionZ = Math.floorDiv(z, LodRegion.REGION_BLOCKS);
		int rings = target.radiusBlocks() / LodRegion.REGION_BLOCKS + 2;
		List<int[]> sections = new ArrayList<>();
		Set<Long> taken = new HashSet<>();
		for (int ring = this.cleanRings; ring <= rings; ring++) {
			boolean clean = true;
			int size = ringSize(ring);
			for (int step = 0; step < size; step++) {
				long key = ringRegion(centerRegionX, centerRegionZ, ring, step);
				int regionX = LodRegion.keyX(key), regionZ = LodRegion.keyZ(key);
				double distance = distanceToRegion(x, z, regionX, regionZ);
				if (distance > target.radiusBlocks()) continue;
				if (this.failed.contains(key)) continue;
				sections.clear();
				sectionsOver(regionX, regionZ, x, z, scheme, sections);
				if (!this.wantsOf(target, generator, key, regionX, regionZ, sections, x, z, reach, scheme, taken, out)) clean = false;
				if (out.size() >= max) return;
			}
			if (clean && ring == this.cleanRings) this.cleanRings = ring + 1;
		}
	}

	/**
	 * Adds what a region still needs. @return whether the region is complete (nothing missing, nothing on its way)
	 */
	private boolean wantsOf(Target target, GlobeWorldGenerator generator, long key, int regionX, int regionZ, List<int[]> sections, int x, int z, double reach, Scheme scheme, Set<Long> taken, List<Want> out) {
		byte[] squares = this.squaresOf(target, regionX, regionZ);
		int minX = regionX * LodRegion.REGION_BLOCKS, minZ = regionZ * LodRegion.REGION_BLOCKS;
		int full = scheme.fullDetailBlocks();
		boolean complete = true;
		for (int[] section : sections) {
			int originX = section[0], originZ = section[1], wanted = section[2];
			int span = SampledColumns.WIDTH << wanted;
			int source = squares == null ? LodRegion.NO_SOURCE : LodRegion.coverageSource(squares, originX - minX, originZ - minZ, originX + span - minX, originZ + span - minZ);
			if (source <= wanted) continue;
			complete = false;
			double distance = distanceToSquare(x, z, originX, originZ, span);
			SectionScheduler.Kind kind;
			int detail = wanted;
			if (distance <= full) {
				kind = SectionScheduler.Kind.MAP_NEAR;
			}
			else if (source >= LodRegion.NO_SOURCE) {
				kind = SectionScheduler.Kind.MAP_HOLE;
				// a far gap: fill it quickly at a coarser level first, refine it later
				if (distance > NEAR_FIELD_BLOCKS + full) detail = Math.min(MAX_DETAIL, wanted + COARSE_FILL_LEVELS);
			}
			else if (distance <= reach) {
				kind = SectionScheduler.Kind.MAP_REFINE;
			}
			else {
				continue; // Distant Horizons is still generating out there; its sections reach the map anyway
			}
			int jobSpan = SampledColumns.WIDTH << detail;
			int jobX = Math.floorDiv(originX, jobSpan) * jobSpan, jobZ = Math.floorDiv(originZ, jobSpan) * jobSpan;
			long sectionKey = sectionKey(jobX, jobZ, detail);
			if (this.outstanding.contains(sectionKey) || !taken.add(sectionKey)) continue;
			// a section Distant Horizons is about to generate at least as finely reaches the map through the feed
			if (SectionScheduler.get().covered(generator, jobX, jobZ, jobSpan, detail)) continue;
			out.add(new Want(key, jobX, jobZ, detail, kind));
		}
		return complete;
	}

	/** Queues one map section on the scheduler. */
	private void submit(Target target, Want want) {
		GlobeWorldGenerator generator = target.generator();
		if (generator == null) return;
		long key = sectionKey(want.originX(), want.originZ(), want.detail());
		if (!this.outstanding.add(key)) return;
		GenProgress progress = this.progress;
		SectionScheduler.get().submit(new SectionScheduler.Job(want.kind(), target, generator.dimensionId, want.originX(), want.originZ(), want.detail()) {

			@Override
			public boolean cancelled() {
				return LodMapBuilder.this.target != target || generator.isClosed() || !LodMapBuilder.this.running;
			}

			@Override
			protected void execute() {
				long start = System.nanoTime();
				try {
					MapSection section = new MapSection(target, want.detail(), want.originX(), want.originZ(), false);
					generator.sampleForMap(want.originX(), want.originZ(), want.detail(), this::cancelled, columns -> section.fill(columns, target.rules()));
					LodMapBuilder.this.built.add(section);
					LodMapBuilder.this.builtSections.incrementAndGet();
				}
				finally {
					LodMapBuilder.this.builtNanos.addAndGet(System.nanoTime() - start);
					LodMapBuilder.this.finish(key);
				}
			}

			@Override
			protected void dropped() {
				LodMapBuilder.this.finish(key);
			}

			@Override
			protected void failed(Throwable throwable) {
				if (!(throwable instanceof CancellationException)) LodMapBuilder.this.failed.add(want.regionKey());
				LodMapBuilder.this.finish(key);
			}

			@Override
			protected @Nullable GenProgress progress() {
				return progress;
			}
		});
	}

	private void finish(long key) {
		this.outstanding.remove(key);
		LockSupport.unpark(this.thread);
	}

	private byte @Nullable [] coverageOf(Target target, int regionX, int regionZ, LodMapStore.Meta meta) {
		long key = LodRegion.key(regionX, regionZ);
		Coverage known = this.coverage.get(key);
		if (known != null && known.revision() == meta.revision()) return known.squares();
		LodRegion region = target.store().load(regionX, regionZ);
		if (region == null) return null;
		byte[] squares = region.coverage();
		this.coverage.put(key, new Coverage(region.revision, squares));
		return squares;
	}

	List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		long live = this.liveSections.get(), built = this.builtSections.get(), dropped = this.droppedSections.get();
		StringBuilder line = new StringBuilder(String.format(
			java.util.Locale.ROOT,
			"Xaero LOD map: %d sections taken from Distant Horizons' generation, %d computed for the map alone (%d on the way)",
			live, built, this.outstanding.size()
		));
		if (built > 0) line.append(String.format(java.util.Locale.ROOT, " (%.0f ms each)", this.builtNanos.get() / 1.0e6D / built));
		line.append(String.format(java.util.Locale.ROOT, ", %d regions saved this session", this.savedRegions.get()));
		if (dropped > 0) line.append(", ").append(dropped).append(" sections skipped while the map was behind");
		int waiting = this.incomingCount.get();
		if (waiting > 0) line.append(", ").append(waiting).append(" waiting");
		lines.add(line.toString());
		return lines;
	}
}
