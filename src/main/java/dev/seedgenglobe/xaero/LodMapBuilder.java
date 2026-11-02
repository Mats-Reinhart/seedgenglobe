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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution;

import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.dh.GlobeWorldGenerator;
import dev.seedgenglobe.structures.LodStructures;
import dev.seedgenglobe.terrain.CanopyModel;
import dev.seedgenglobe.terrain.GlobeTerrain;
import dev.seedgenglobe.terrain.GroundCover;
import dev.seedgenglobe.terrain.SampledColumns;
import dev.seedgenglobe.terrain.SectionSampler;

/**
 * Background thread that keeps the LOD surface map in {@link LodMapStore} filled.
 * <p>
 * The map is drawn from the same sections Distant Horizons gets: every section SeedGenGlobe generates for it is read
 * the way Xaero reads a chunk column (on the generating thread, see {@link MapFeed}) and merged here into the regions
 * it covers, keeping the finest data each cell has seen. Regions that changed are saved after a few seconds and handed
 * to the {@link XaeroLodWriter map writer}, so the map grows with the LODs, in the same detail levels.
 * <p>
 * Distant Horizons does not generate what it already has, so areas whose LODs were made before the map existed, or that
 * come from a Distant Horizons server, are left to this thread itself: it computes the sections Distant Horizons would
 * show there (same detail by distance, following its horizontal quality setting) one at a time, nearest first, but
 * only where the map has nothing as fine yet, only behind the area Distant Horizons is generating right now, and never
 * while sections from Distant Horizons are waiting.
 * <p>
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
	/** The scan starts over from the nearest region once the player moved this far. */
	static final int RESCAN_DISTANCE = 128;
	/** A region that received data is saved and handed to the writer this long after its first change. */
	static final long PUBLISH_MILLIS = 3_000L;
	/** Regions that received nothing for this long are saved if needed and let go. */
	static final long IDLE_MILLIS = 20_000L;
	/** Cells all regions being merged may hold together, about 16 bytes each (8 regions at one pixel per block). */
	static final long CANVAS_CELLS = 1L << 21;
	/** Cells the store keeps in memory for the writer. */
	static final long STORE_CACHE_CELLS = 1L << 21;
	/** Sections from Distant Horizons waiting to be merged; beyond this they are dropped, and the builder fills the gap later. */
	static final int MAX_INCOMING = 256;
	/** How far behind the nearest section Distant Horizons generated lately the builder stays. */
	static final double FRONT_MARGIN = 768.0D;
	/** Distant Horizons counts as generating while its last section is at most this old. */
	static final long FRONT_MILLIS = 20_000L;

	/** One region's summary of how fine its data is, for the revision it was computed from. */
	private record Coverage(long revision, byte[] squares) {}

	/** A section the builder will compute. */
	private record Job(long regionKey, int originX, int originZ, int detail) {}

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
	private final Thread thread;

	// builder thread state
	private @Nullable Target samplerTarget;
	private @Nullable SectionSampler sampler;
	private @Nullable LodMapStore canvasStore;
	private final Map<Long, RegionCanvas> canvases = new HashMap<>();
	private final Map<Long, Coverage> coverage = new HashMap<>();
	private final Set<Long> failed = new HashSet<>();
	private int scanCenterX = Integer.MIN_VALUE, scanCenterZ = Integer.MIN_VALUE, cleanRings;
	private double frontDistance = Double.POSITIVE_INFINITY;
	private long frontAt, lastLiveAt;

	final AtomicLong liveSections = new AtomicLong(), droppedSections = new AtomicLong();
	final AtomicLong builtSections = new AtomicLong(), builtNanos = new AtomicLong(), savedRegions = new AtomicLong();

	/**
	 * Merging and saving are cheap and must keep up with generation, so the thread runs just below normal priority,
	 * like the generator threads; it drops to the lowest priority only while it computes terrain of its own.
	 */
	static final int MERGE_PRIORITY = Thread.NORM_PRIORITY - 1, BUILD_PRIORITY = Thread.MIN_PRIORITY;
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
		MapFeed oldFeed = this.feed;
		if (previous != null && previous.generator() != null && oldFeed != null && previous.generator().sectionListener() == oldFeed) {
			previous.generator().setSectionListener(null);
		}
		this.target = target;
		MapFeed feed = target != null && target.generator() != null ? new MapFeed(this, target) : null;
		this.feed = feed;
		if (feed != null) target.generator().setSectionListener(feed);
		this.loadRequests.clear();
		this.requested.clear();
		this.computed.clear();
		LockSupport.unpark(this.thread);
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
	record Scheme(double unitBlocks, double logBase, int finest) {

		static final Scheme MEDIUM = new Scheme(12 * 16, Math.log(2.0D), 0);

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
		double cx = originX + span / 2.0D - x, cz = originZ + span / 2.0D - z;
		int wanted = scheme.detailAt(Math.sqrt(cx * cx + cz * cz));
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
				if (!this.incoming.isEmpty()) continue;
				if (this.buildStep(target, now)) continue;
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
		while (cells > CANVAS_CELLS && this.canvases.size() > 1) {
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

	// ------------------------------------------------------------------------------------------------ filling holes

	/** How far out the builder may work: everywhere, or only behind the area Distant Horizons is generating. */
	private double reach(Target target, long now) {
		if (now - this.lastLiveAt > FRONT_MILLIS) return target.radiusBlocks();
		return Math.min(target.radiusBlocks(), this.frontDistance - FRONT_MARGIN);
	}

	/** Computes one section the map is missing. @return whether one was computed */
	private boolean buildStep(Target target, long now) {
		if (!this.incoming.isEmpty()) return false;
		double reach = this.reach(target, now);
		if (reach < 0.0D) return false;
		Job job = this.findJob(target, reach);
		if (job == null) return false;
		long start = System.nanoTime();
		try {
			SectionSampler sampler = this.sampler(target);
			this.thread.setPriority(BUILD_PRIORITY);
			try {
				sampler.sampleSection(job.originX(), job.originZ(), job.detail(), target.levelMinY(), target.levelMaxY(), target.topSkyLight());
			}
			finally {
				this.thread.setPriority(MERGE_PRIORITY);
			}
			MapSection section = new MapSection(target, job.detail(), job.originX(), job.originZ(), false);
			section.fill(sampler, target.rules());
			// make sure the region of the job takes it even when its data is only as coarse as the section
			if (!this.canvases.containsKey(job.regionKey())) this.openCanvas(target, LodRegion.keyX(job.regionKey()), LodRegion.keyZ(job.regionKey()), job.detail());
			this.merge(target, section, System.currentTimeMillis());
			this.builtSections.incrementAndGet();
		}
		catch (RuntimeException | StackOverflowError exception) {
			this.failed.add(job.regionKey());
			SeedGenGlobe.LOGGER.warn(
				"SeedGenGlobe: could not compute the LOD map at {} {} (detail {}): {}",
				job.originX(), job.originZ(), job.detail(), exception.toString()
			);
		}
		finally {
			this.builtNanos.addAndGet(System.nanoTime() - start);
		}
		return true;
	}

	/** The next section to compute: in the nearest region within reach whose data is coarser than Distant Horizons would show. */
	private @Nullable Job findJob(Target target, double reach) {
		int x = this.centerX, z = this.centerZ;
		if (Math.abs(x - this.scanCenterX) > RESCAN_DISTANCE || Math.abs(z - this.scanCenterZ) > RESCAN_DISTANCE) {
			// the player moved: wanted detail levels changed, so rings that were complete may want finer data now
			this.scanCenterX = x;
			this.scanCenterZ = z;
			this.cleanRings = 0;
		}
		Scheme scheme = Scheme.current();
		int centerRegionX = Math.floorDiv(x, LodRegion.REGION_BLOCKS), centerRegionZ = Math.floorDiv(z, LodRegion.REGION_BLOCKS);
		int rings = target.radiusBlocks() / LodRegion.REGION_BLOCKS + 2;
		List<int[]> sections = new ArrayList<>();
		for (int ring = this.cleanRings; ring <= rings; ring++) {
			boolean clean = true;
			int size = ringSize(ring);
			for (int step = 0; step < size; step++) {
				long key = ringRegion(centerRegionX, centerRegionZ, ring, step);
				int regionX = LodRegion.keyX(key), regionZ = LodRegion.keyZ(key);
				double distance = distanceToRegion(x, z, regionX, regionZ);
				if (distance > target.radiusBlocks()) continue;
				if (distance > reach) {
					clean = false;
					continue;
				}
				if (this.failed.contains(key)) continue;
				sections.clear();
				sectionsOver(regionX, regionZ, x, z, scheme, sections);
				int[] missing = this.firstMissing(target, regionX, regionZ, sections);
				if (missing != null) return new Job(key, missing[0], missing[1], missing[2]);
			}
			if (clean && ring == this.cleanRings) this.cleanRings = ring + 1;
		}
		return null;
	}

	/** The first of the sections the region has coarser data than (or no data) for, or null when it is complete. */
	private int @Nullable [] firstMissing(Target target, int regionX, int regionZ, List<int[]> sections) {
		long key = LodRegion.key(regionX, regionZ);
		RegionCanvas canvas = this.canvases.get(key);
		LodMapStore.Meta meta = target.store().meta(regionX, regionZ);
		if (canvas == null && meta == null) return sections.isEmpty() ? null : sections.get(0);
		int finest = Integer.MAX_VALUE;
		for (int[] section : sections) finest = Math.min(finest, section[2]);
		int maxSource = canvas != null ? canvas.maxSource() : meta.maxSource();
		if (maxSource <= finest) return null;
		byte[] squares = canvas != null ? canvas.coverage() : this.coverageOf(target, regionX, regionZ, meta);
		if (squares == null) return sections.isEmpty() ? null : sections.get(0);
		int minX = regionX * LodRegion.REGION_BLOCKS, minZ = regionZ * LodRegion.REGION_BLOCKS;
		for (int[] section : sections) {
			int span = SampledColumns.WIDTH << section[2];
			int source = LodRegion.coverageSource(squares, section[0] - minX, section[1] - minZ, section[0] + span - minX, section[1] + span - minZ);
			if (source > section[2]) return section;
		}
		return null;
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

	private SectionSampler sampler(Target target) {
		if (this.sampler == null || this.samplerTarget != target) {
			this.sampler = new SectionSampler(target.terrain(), target.canopy(), target.ground(), target.structures());
			this.samplerTarget = target;
			this.failed.clear();
		}
		return this.sampler;
	}

	List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		long live = this.liveSections.get(), built = this.builtSections.get(), dropped = this.droppedSections.get();
		StringBuilder line = new StringBuilder(String.format(
			java.util.Locale.ROOT,
			"Xaero LOD map: %d sections taken from Distant Horizons' generation, %d computed for the map alone",
			live, built
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
