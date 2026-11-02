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
import dev.seedgenglobe.terrain.CanopyModel;
import dev.seedgenglobe.terrain.GlobeTerrain;
import dev.seedgenglobe.terrain.SectionSampler;

/**
 * The Distant Horizons world generator for one Big Globe level.
 * <p>
 * It returns {@link EDhApiWorldGeneratorReturnType#API_DATA_SOURCES}, so Distant Horizons asks for whole LOD
 * sections at the detail level it will render them at. A far section therefore costs 4096 column evaluations no
 * matter how many chunks it covers, instead of generating every one of those chunks. That is where the
 * exponential speedup comes from: the work per ring of terrain stays constant as rings get wider.
 */
public final class GlobeWorldGenerator implements IDhApiWorldGenerator {

	public final IDhApiLevelWrapper level;
	public final GlobeTerrain terrain;
	public final GenStats stats;
	private final DhWriter.Wrappers wrappers;
	private final @Nullable CanopyModel.Shared canopy;
	private final ConcurrentLinkedQueue<Worker> idle = new ConcurrentLinkedQueue<>();
	private final int levelMinY, levelMaxY;
	private final byte topSkyLight;
	private final byte largestDetail;
	private final int priority;
	private volatile boolean closed;

	private final class Worker {
		final SectionSampler sampler = new SectionSampler(GlobeWorldGenerator.this.terrain, GlobeWorldGenerator.this.canopy);
		final DhWriter writer = new DhWriter(GlobeWorldGenerator.this.wrappers);
	}

	public GlobeWorldGenerator(IDhApiLevelWrapper level, GlobeTerrain terrain, boolean hasSkyLight, boolean hasCeiling, String statsName) {
		this.level = level;
		this.terrain = terrain;
		GlobeConfig config = GlobeConfig.get();
		this.stats = new GenStats(statsName);
		this.wrappers = new DhWriter.Wrappers(level, terrain.fallbackBiome);
		this.canopy = config.trees ? CanopyModel.Shared.create(terrain, hasSkyLight, hasCeiling) : null;
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
		return this.generateAsync(chunkPosMinX << 4, chunkPosMinZ << 4, detailLevel, pooledFullDataSource, worldGeneratorThreadPool, resultConsumer);
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
		return CompletableFuture.runAsync(
			() -> {
				this.generateNow(originX, originZ, detailLevel, dataSource);
				if (resultConsumer != null) resultConsumer.accept(dataSource);
			},
			executor
		);
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
		try {
			worker.sampler.sampleSection(originX, originZ, detailLevel, this.levelMinY, this.levelMaxY, this.topSkyLight);
			errors = worker.sampler.lastErrors;
			worker.writer.writeSection(worker.sampler, dataSource, this.levelMinY);
		}
		finally {
			if (!this.closed) this.idle.offer(worker);
		}
		if (record) {
			int width = dataSource.getWidthInDataColumns();
			this.stats.record(detailLevel, width * width, System.nanoTime() - start, errors);
		}
	}

	/** A private sampler for diagnostics; never shared with the generation workers. */
	public SectionSampler newSampler() {
		return new SectionSampler(this.terrain, this.canopy);
	}

	public byte topSkyLight() {
		return this.topSkyLight;
	}

	@Override
	public void preGeneratorTaskStart() {}

	@Override
	public void close() {
		this.closed = true;
		this.idle.clear();
	}

	public boolean isClosed() {
		return this.closed;
	}
}
