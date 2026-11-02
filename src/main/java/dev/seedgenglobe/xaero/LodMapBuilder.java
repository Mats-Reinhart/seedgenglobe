package dev.seedgenglobe.xaero;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.terrain.CanopyModel;
import dev.seedgenglobe.terrain.GlobeTerrain;
import dev.seedgenglobe.terrain.GroundCover;
import dev.seedgenglobe.terrain.SectionSampler;

/**
 * Background thread that computes the LOD surface map around the player and keeps {@link LodMapStore} filled.
 * <p>
 * Regions are computed nearest first, coarser with distance the way Distant Horizons' LODs are: one map pixel
 * per block within 768 blocks, then 2, 4, 8 ... up to 64 blocks per pixel. Each region is computed once and
 * stored; it is recomputed only when the player comes close enough to want a finer version. Terrain comes from
 * the same samplers that generate Distant Horizons' LODs (with tree canopies and ground cover), so map and LODs agree.
 * <p>
 * Also reads stored regions from disk for the map writer, so the render thread never touches the disk.
 */
public final class LodMapBuilder implements Runnable {

	/** Everything the builder needs for one dimension. */
	public record Target(
		LodMapStore store,
		GlobeTerrain terrain,
		@Nullable CanopyModel.Shared canopy,
		@Nullable GroundCover.Shared ground,
		int levelMinY,
		int levelMaxY,
		byte topSkyLight,
		int radiusBlocks,
		XaeroPixel.Rules rules
	) {}

	static final int MAX_DETAIL = 6;
	/** The scan starts over from the nearest region once the player moved this far. */
	static final int RESCAN_DISTANCE = 128;

	private volatile @Nullable Target target;
	private volatile int centerX, centerZ;
	private volatile boolean running = true;
	private final ConcurrentLinkedQueue<Long> loadRequests = new ConcurrentLinkedQueue<>();
	private final Set<Long> requested = ConcurrentHashMap.newKeySet();
	/** Regions that were just computed (or recomputed finer), for the writer. */
	final ConcurrentLinkedQueue<Long> computed = new ConcurrentLinkedQueue<>();
	/** Small jobs (file writes) handed over from the render thread. */
	private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
	private final Thread thread;

	// builder thread state
	private @Nullable Target samplerTarget;
	private @Nullable SectionSampler sampler;
	private final MapPixel pixel = new MapPixel();
	private final Map<Long, Section> sections = new LinkedHashMap<>(8, 0.75F, true);
	private final Set<Long> failed = new HashSet<>();
	private int scanCenterX = Integer.MIN_VALUE, scanCenterZ = Integer.MIN_VALUE, cleanRings;

	final AtomicLong computedRegions = new AtomicLong(), computedColumns = new AtomicLong(), computeNanos = new AtomicLong();

	/** One computed LOD section: 64 x 64 map pixels. */
	static final class Section {

		static final int SIZE = SectionSampler.WIDTH * SectionSampler.WIDTH;

		final int detail, originX, originZ;
		final BlockState[] state = new BlockState[SIZE];
		final int[] height = new int[SIZE];
		final int[] topHeight = new int[SIZE];
		@SuppressWarnings("unchecked")
		final ResourceKey<Biome>[] biome = new ResourceKey[SIZE];
		final BlockState[] overlay = new BlockState[SIZE];
		final int[] overlayDepth = new int[SIZE];
		final BlockState[] overlay2 = new BlockState[SIZE];
		final int[] overlayDepth2 = new int[SIZE];
		final byte[] light = new byte[SIZE];

		Section(int detail, int originX, int originZ) {
			this.detail = detail;
			this.originX = originX;
			this.originZ = originZ;
		}

		void store(int index, MapPixel pixel) {
			this.state[index] = pixel.state;
			this.height[index] = pixel.height;
			this.topHeight[index] = pixel.topHeight;
			this.biome[index] = pixel.biome;
			this.overlay[index] = pixel.overlay;
			this.overlayDepth[index] = pixel.overlayDepth;
			this.overlay2[index] = pixel.overlay2;
			this.overlayDepth2[index] = pixel.overlayDepth2;
			this.light[index] = pixel.light;
		}

		void load(int index, MapPixel pixel) {
			pixel.state = this.state[index];
			pixel.height = this.height[index];
			pixel.topHeight = this.topHeight[index];
			pixel.biome = this.biome[index];
			pixel.overlay = this.overlay[index];
			pixel.overlayDepth = this.overlayDepth[index];
			pixel.overlay2 = this.overlay2[index];
			pixel.overlayDepth2 = this.overlayDepth2[index];
			pixel.light = this.light[index];
		}
	}

	public LodMapBuilder() {
		this.thread = new Thread(this, "SeedGenGlobe LOD Map");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
		this.thread.start();
	}

	public void setTarget(@Nullable Target target) {
		this.target = target;
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

	/** Pixels per block side for a region whose nearest point is this far from the player. */
	static int detailFor(double distance) {
		if (distance < 768.0D) return 0;
		int ratio = (int)(Math.min(distance / 768.0D, 1 << 20)); // 1 from 768 blocks, 2 from 1536, 4 from 3072 ...
		return Math.min(1 + (31 - Integer.numberOfLeadingZeros(ratio)), MAX_DETAIL);
	}

	static double distanceToRegion(int x, int z, int regionX, int regionZ) {
		int minX = regionX * LodRegion.REGION_BLOCKS, minZ = regionZ * LodRegion.REGION_BLOCKS;
		double dx = x < minX ? minX - x : Math.max(0, x - (minX + LodRegion.REGION_BLOCKS - 1));
		double dz = z < minZ ? minZ - z : Math.max(0, z - (minZ + LodRegion.REGION_BLOCKS - 1));
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
				if (target == null) {
					LockSupport.parkNanos(1_000_000_000L);
					continue;
				}
				Long load = this.loadRequests.poll();
				if (load != null) {
					target.store().load(LodRegion.keyX(load), LodRegion.keyZ(load));
					this.requested.remove(load);
					continue;
				}
				long next = this.findNext(target);
				if (next == Long.MIN_VALUE) {
					LockSupport.parkNanos(500_000_000L);
					continue;
				}
				this.compute(target, LodRegion.keyX(next), LodRegion.keyZ(next));
			}
			catch (Throwable throwable) {
				if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: the LOD map builder hit an error; it pauses for a few seconds.", throwable);
				LockSupport.parkNanos(5_000_000_000L);
			}
		}
	}

	/** The nearest region that is missing or stored coarser than it should be, or Long.MIN_VALUE. */
	private long findNext(Target target) {
		int x = this.centerX, z = this.centerZ;
		if (Math.abs(x - this.scanCenterX) > RESCAN_DISTANCE || Math.abs(z - this.scanCenterZ) > RESCAN_DISTANCE) {
			// the player moved: distances changed, so rings that were complete may want finer regions now
			this.scanCenterX = x;
			this.scanCenterZ = z;
			this.cleanRings = 0;
		}
		int centerRegionX = Math.floorDiv(x, LodRegion.REGION_BLOCKS), centerRegionZ = Math.floorDiv(z, LodRegion.REGION_BLOCKS);
		int radius = target.radiusBlocks();
		int rings = radius / LodRegion.REGION_BLOCKS + 2;
		LodMapStore store = target.store();
		for (int ring = this.cleanRings; ring <= rings; ring++) {
			int size = ringSize(ring);
			for (int step = 0; step < size; step++) {
				long key = ringRegion(centerRegionX, centerRegionZ, ring, step);
				int regionX = LodRegion.keyX(key), regionZ = LodRegion.keyZ(key);
				double distance = distanceToRegion(x, z, regionX, regionZ);
				if (distance > radius) continue;
				int stored = store.detail(regionX, regionZ);
				if (stored >= 0 && stored <= detailFor(distance)) continue;
				if (this.failed.contains(key)) continue;
				return key;
			}
			// every region of this ring is as detailed as it needs to be
			if (ring == this.cleanRings) this.cleanRings = ring + 1;
		}
		return Long.MIN_VALUE;
	}

	private SectionSampler sampler(Target target) {
		if (this.sampler == null || this.samplerTarget != target) {
			this.sampler = new SectionSampler(target.terrain(), target.canopy(), target.ground());
			this.samplerTarget = target;
			this.sections.clear();
			this.failed.clear();
		}
		return this.sampler;
	}

	private void compute(Target target, int regionX, int regionZ) {
		long start = System.nanoTime();
		double distance = distanceToRegion(this.centerX, this.centerZ, regionX, regionZ);
		int detail = detailFor(distance);
		long key = LodRegion.key(regionX, regionZ);
		try {
			LodRegion.Builder builder = new LodRegion.Builder(regionX, regionZ, detail);
			int sectionBlocks = SectionSampler.WIDTH << detail;
			int regionMinX = regionX * LodRegion.REGION_BLOCKS, regionMinZ = regionZ * LodRegion.REGION_BLOCKS;
			int firstSectionX = Math.floorDiv(regionMinX, sectionBlocks), firstSectionZ = Math.floorDiv(regionMinZ, sectionBlocks);
			int lastSectionX = Math.floorDiv(regionMinX + LodRegion.REGION_BLOCKS - 1, sectionBlocks);
			int lastSectionZ = Math.floorDiv(regionMinZ + LodRegion.REGION_BLOCKS - 1, sectionBlocks);
			MapPixel pixel = this.pixel;
			for (int sectionX = firstSectionX; sectionX <= lastSectionX; sectionX++) {
				for (int sectionZ = firstSectionZ; sectionZ <= lastSectionZ; sectionZ++) {
					Section section = this.section(target, detail, sectionX * sectionBlocks, sectionZ * sectionBlocks);
					for (int relX = 0; relX < SectionSampler.WIDTH; relX++) {
						int blockX = section.originX + (relX << detail);
						if (blockX < regionMinX || blockX >= regionMinX + LodRegion.REGION_BLOCKS) continue;
						for (int relZ = 0; relZ < SectionSampler.WIDTH; relZ++) {
							int blockZ = section.originZ + (relZ << detail);
							if (blockZ < regionMinZ || blockZ >= regionMinZ + LodRegion.REGION_BLOCKS) continue;
							section.load(relX * SectionSampler.WIDTH + relZ, pixel);
							builder.set((blockX - regionMinX) >> detail, (blockZ - regionMinZ) >> detail, pixel);
						}
					}
				}
			}
			target.store().save(builder.build());
			this.computed.add(key);
			this.computedRegions.incrementAndGet();
		}
		catch (RuntimeException | StackOverflowError exception) {
			this.failed.add(key);
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: could not compute the LOD map for region {} {}: {}", regionX, regionZ, exception.toString());
		}
		finally {
			this.computeNanos.addAndGet(System.nanoTime() - start);
		}
	}

	private Section section(Target target, int detail, int originX, int originZ) {
		long key = ((long)(detail) << 58) ^ (((long)(originX) & 0x1FFFFFFFL) << 29) ^ ((long)(originZ) & 0x1FFFFFFFL);
		Section section = this.sections.get(key);
		if (section != null) return section;
		SectionSampler sampler = this.sampler(target);
		sampler.sampleSection(originX, originZ, detail, target.levelMinY(), target.levelMaxY(), target.topSkyLight());
		this.computedColumns.addAndGet(Section.SIZE);
		section = new Section(detail, originX, originZ);
		MapPixel pixel = this.pixel;
		for (int relX = 0; relX < SectionSampler.WIDTH; relX++) {
			for (int relZ = 0; relZ < SectionSampler.WIDTH; relZ++) {
				XaeroPixel.toPixel(sampler.profile(relX, relZ), target.rules(), pixel);
				section.store(relX * SectionSampler.WIDTH + relZ, pixel);
			}
		}
		this.sections.put(key, section);
		while (this.sections.size() > 6) {
			this.sections.remove(this.sections.keySet().iterator().next());
		}
		return section;
	}
}
