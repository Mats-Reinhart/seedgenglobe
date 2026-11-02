package dev.seedgenglobe.dh;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.structures.LodStructures;
import dev.seedgenglobe.terrain.CanopyModel;
import dev.seedgenglobe.terrain.GlobeTerrain;
import dev.seedgenglobe.terrain.GroundCover;
import dev.seedgenglobe.terrain.SampledColumns;
import dev.seedgenglobe.terrain.SectionSampler;

/**
 * The Distant Horizons world generator for one Big Globe level.
 * <p>
 * It returns {@link EDhApiWorldGeneratorReturnType#API_DATA_SOURCES}, so Distant Horizons asks for whole LOD
 * sections at the detail level it will render them at. A far section therefore costs 4096 column evaluations no
 * matter how many chunks it covers, instead of generating every one of those chunks. That is where the
 * exponential speedup comes from: the work per ring of terrain stays constant as rings get wider.
 * <p>
 * Sections run on {@link GeneratorThreads} (SeedGenGlobe's own threads unless configured otherwise), and every
 * section is offered to a {@link SectionListener}, which is how the Xaero LOD map is drawn from exactly the terrain
 * Distant Horizons receives, as it receives it.
 */
public final class GlobeWorldGenerator implements IDhApiWorldGenerator {

	/** Sees every section right after it was computed. */
	@FunctionalInterface
	public interface SectionListener {

		/**
		 * Called on the generating thread. The columns belong to the generator and are only valid during the call.
		 *
		 * @param detail LOD detail level: every column is 2^detail blocks wide
		 */
		void sectionGenerated(GlobeWorldGenerator generator, int originX, int originZ, int detail, SampledColumns columns);
	}

	public final IDhApiLevelWrapper level;
	public final GlobeTerrain terrain;
	public final GenStats stats;
	private final DhWriter.Wrappers wrappers;
	private final @Nullable CanopyModel.Shared canopy;
	private final @Nullable GroundCover.Shared ground;
	private final @Nullable LodStructures structures;
	private final ConcurrentLinkedQueue<Worker> idle = new ConcurrentLinkedQueue<>();
	private final int levelMinY, levelMaxY;
	private final byte topSkyLight;
	private final byte largestDetail;
	private final int priority;
	private volatile boolean closed;
	private volatile @Nullable SectionListener listener;
	private volatile int listenerFailures;

	private final class Worker {
		final SectionSampler sampler = GlobeWorldGenerator.this.newSampler();
		final DhWriter writer = new DhWriter(GlobeWorldGenerator.this.wrappers);
	}

	/**
	 * @param ground ground cover for this terrain, or null for none (see {@link GroundCover.Shared#create})
	 * @param structures Big Globe's structures for this level, or null for none (see {@link LodStructures#create})
	 */
	public GlobeWorldGenerator(IDhApiLevelWrapper level, GlobeTerrain terrain, boolean hasSkyLight, boolean hasCeiling, String statsName, @Nullable GroundCover.Shared ground, @Nullable LodStructures structures) {
		this.level = level;
		this.terrain = terrain;
		GlobeConfig config = GlobeConfig.get();
		this.stats = new GenStats(statsName);
		this.wrappers = new DhWriter.Wrappers(level, terrain.fallbackBiome);
		this.canopy = config.trees ? CanopyModel.Shared.create(terrain, hasSkyLight, hasCeiling) : null;
		this.ground = config.groundCover ? ground : null;
		this.structures = config.structures ? structures : null;
		// DH's level wrappers return the level's height from getMaxHeight(), not its top Y.
		int minY = level.getMinHeight();
		int height = level.getMaxHeight();
		this.levelMinY = minY;
		this.levelMaxY = minY + height;
		this.topSkyLight = (byte)(hasSkyLight ? 15 : 0);
		this.largestDetail = (byte)(config.maxDetailLevel);
		this.priority = config.generatorPriority;
	}

	public int levelMinY() {
		return this.levelMinY;
	}

	public int levelMaxY() {
		return this.levelMaxY;
	}

	@Override
	public int getPriority() {
		return this.priority;
	}

	@Override
	public byte getSmallestDataDetailLevel() {
		return 0;
	}

	@Override
	public byte getLargestDataDetailLevel() {
		return this.largestDetail;
	}

	@Override
	public EDhApiWorldGeneratorReturnType getReturnType() {
		return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
	}

	@Override
	public boolean runApiValidation() {
		// columns are built contiguous, top-down and inside the level by construction.
		return false;
	}

	@Override
	public CompletableFuture<Void> generateLod(
		int chunkPosMinX,
		int chunkPosMinZ,
		int lodPosX,
		int lodPosZ,
		byte detailLevel,
		IDhApiFullDataSource pooledFullDataSource,
		EDhApiDistantGeneratorMode generatorMode,
		ExecutorService worldGeneratorThreadPool,
		Consumer<IDhApiFullDataSource> resultConsumer
	) {
		// The generator mode is ignored on purpose: LODs computed here are never saved as chunks,
		// so "pre-existing chunks only" has nothing to protect, and skipping would leave holes DH keeps re-requesting.
		SectionFlags.keepToItself(pooledFullDataSource);
		Executor own = GeneratorThreads.executor();
		return this.generateAsync(chunkPosMinX << 4, chunkPosMinZ << 4, detailLevel, pooledFullDataSource, own != null ? own : worldGeneratorThreadPool, resultConsumer);
	}

	public CompletableFuture<Void> generateAsync(
		int originX,
		int originZ,
		byte detailLevel,
		IDhApiFullDataSource dataSource,
		Executor executor,
		@Nullable Consumer<IDhApiFullDataSource> resultConsumer
	) {
		if (this.closed) {
			CompletableFuture<Void> future = new CompletableFuture<>();
			future.completeExceptionally(new CancellationException("SeedGenGlobe generator closed"));
			return future;
		}
		this.stats.waiting.incrementAndGet();
		try {
			return CompletableFuture.runAsync(
				() -> {
					this.stats.waiting.decrementAndGet();
					this.generateNow(originX, originZ, detailLevel, dataSource);
					if (resultConsumer != null) resultConsumer.accept(dataSource);
				},
				executor
			);
		}
		catch (RuntimeException rejected) {
			this.stats.waiting.decrementAndGet();
			throw rejected;
		}
	}

	/** Generates one section on the calling thread. */
	public void generateNow(int originX, int originZ, byte detailLevel, IDhApiFullDataSource dataSource) {
		this.generateNow(originX, originZ, detailLevel, dataSource, true);
	}

	/**
	 * Generates one section on the calling thread.
	 *
	 * @param record false for diagnostics (the benchmark command), so they do not show up in the generation statistics
	 */
	public void generateNow(int originX, int originZ, byte detailLevel, IDhApiFullDataSource dataSource, boolean record) {
		if (this.closed) throw new CancellationException("SeedGenGlobe generator closed");
		long start = System.nanoTime();
		Worker worker = this.idle.poll();
		if (worker == null) worker = new Worker();
		int errors;
		if (record) this.stats.running.incrementAndGet();
		try {
			worker.sampler.sampleSection(originX, originZ, detailLevel, this.levelMinY, this.levelMaxY, this.topSkyLight);
			errors = worker.sampler.lastErrors;
			worker.writer.writeSection(worker.sampler, dataSource, this.levelMinY);
			if (record) this.tellListener(originX, originZ, detailLevel, worker.sampler);
		}
		finally {
			if (record) this.stats.running.decrementAndGet();
			if (!this.closed) this.idle.offer(worker);
		}
		if (record) {
			int width = dataSource.getWidthInDataColumns();
			this.stats.record(detailLevel, width * width, System.nanoTime() - start, errors);
		}
	}

	/** Sets (or with null removes) the listener that sees every generated section. */
	public void setSectionListener(@Nullable SectionListener listener) {
		this.listener = listener;
	}

	public @Nullable SectionListener sectionListener() {
		return this.listener;
	}

	private void tellListener(int originX, int originZ, int detail, SampledColumns columns) {
		SectionListener listener = this.listener;
		if (listener == null) return;
		try {
			listener.sectionGenerated(this, originX, originZ, detail, columns);
		}
		catch (Throwable throwable) {
			if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
			// the section itself is fine and goes to Distant Horizons; only the listener misses it
			int failures = ++this.listenerFailures;
			if (failures <= 3 || Integer.bitCount(failures) == 1) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe [{}]: a section listener failed ({} times so far)", this.stats.name, failures, throwable);
			}
		}
	}

	/** A new sampler for this terrain, for a worker or for diagnostics. */
	public SectionSampler newSampler() {
		return new SectionSampler(this.terrain, this.canopy, this.ground, this.structures);
	}

	public @Nullable CanopyModel.Shared canopy() {
		return this.canopy;
	}

	public @Nullable GroundCover.Shared groundCover() {
		return this.ground;
	}

	public @Nullable LodStructures structures() {
		return this.structures;
	}

	public byte topSkyLight() {
		return this.topSkyLight;
	}

	@Override
	public void preGeneratorTaskStart() {}

	@Override
	public void close() {
		this.closed = true;
		this.listener = null;
		this.idle.clear();
		this.stats.close();
	}

	public boolean isClosed() {
		return this.closed;
	}
}
