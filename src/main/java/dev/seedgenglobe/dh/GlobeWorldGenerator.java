package dev.seedgenglobe.dh;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.gen.GenProgress;
import dev.seedgenglobe.gen.SectionScheduler;
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
 * Every request becomes a job on the {@link SectionScheduler}, which runs the sections nearest to the player first
 * on SeedGenGlobe's own threads. Distant Horizons only withdraws requests it has not handed out yet, so a request
 * stays wanted until it is done; closing the generator stops all of its sections within a fraction of a second
 * (dropped before they start or stopped part-way), before the server takes away what they need. Every section is offered to a {@link SectionListener},
 * which is how the Xaero LOD map is drawn from exactly the terrain Distant Horizons receives, as it receives it.
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
	/** The level's identifier, for nearest-first ordering ({@link dev.seedgenglobe.gen.Focus}). */
	public final String dimensionId;
	public final GenProgress progress;
	private final DhWriter.Wrappers wrappers;
	private final @Nullable CanopyModel.Shared canopy;
	private final @Nullable GroundCover.Shared ground;
	private final @Nullable LodStructures structures;
	private final ConcurrentLinkedQueue<Worker> idle = new ConcurrentLinkedQueue<>();
	private final int levelMinY, levelMaxY;
	private final byte topSkyLight;
	private final byte largestDetail;
	private final int priority;
	private final SectionScheduler.Kind requestKind;
	private volatile boolean closed;
	private volatile @Nullable SectionListener listener;
	private volatile @Nullable Runnable onClose;
	private volatile int listenerFailures;

	private final class Worker {
		final SectionSampler sampler = GlobeWorldGenerator.this.newSampler();
		final DhWriter writer = new DhWriter(GlobeWorldGenerator.this.wrappers);
		final long[] phases = new long[GenProgress.Phase.values().length];
	}

	/**
	 * @param dimensionId the level's identifier
	 * @param client whether this generator answers a multiplayer client's own requests
	 * @param ground ground cover for this terrain, or null for none (see {@link GroundCover.Shared#create})
	 * @param structures Big Globe's structures for this level, or null for none (see {@link LodStructures#create})
	 */
	public GlobeWorldGenerator(IDhApiLevelWrapper level, GlobeTerrain terrain, String dimensionId, boolean client, boolean hasSkyLight, boolean hasCeiling, String statsName, @Nullable GroundCover.Shared ground, @Nullable LodStructures structures) {
		this.level = level;
		this.terrain = terrain;
		this.dimensionId = dimensionId;
		this.requestKind = client ? SectionScheduler.Kind.CLIENT : SectionScheduler.Kind.DISTANT_HORIZONS;
		GlobeConfig config = GlobeConfig.get();
		this.progress = new GenProgress(statsName, dimensionId, this, false, DhBridge::renderDistanceBlocks);
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
		return this.generateAsync(chunkPosMinX << 4, chunkPosMinZ << 4, detailLevel, pooledFullDataSource, resultConsumer);
	}

	/**
	 * Queues a section on the scheduler. The future completes when the section was written into the data source (and
	 * the consumer, if any, was called), and exceptionally with a {@link CancellationException} when it was dropped.
	 * Cancelling the future drops the section if it has not started, and stops it part-way otherwise.
	 */
	public CompletableFuture<Void> generateAsync(int originX, int originZ, byte detailLevel, IDhApiFullDataSource dataSource, @Nullable Consumer<IDhApiFullDataSource> resultConsumer) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		if (this.closed) {
			future.completeExceptionally(new CancellationException("SeedGenGlobe generator closed"));
			return future;
		}
		SectionScheduler.get().submit(new SectionScheduler.Job(this.requestKind, this, this.dimensionId, originX, originZ, detailLevel) {

			@Override
			public boolean cancelled() {
				return future.isDone() || GlobeWorldGenerator.this.closed;
			}

			@Override
			protected void execute() {
				GlobeWorldGenerator.this.generateNow(originX, originZ, detailLevel, dataSource, true, this::cancelled);
				if (resultConsumer != null) resultConsumer.accept(dataSource);
				future.complete(null);
			}

			@Override
			protected void dropped() {
				future.completeExceptionally(new CancellationException("SeedGenGlobe section no longer wanted"));
			}

			@Override
			protected void failed(Throwable throwable) {
				future.completeExceptionally(throwable);
			}

			@Override
			protected GenProgress progress() {
				return GlobeWorldGenerator.this.progress;
			}
		});
		return future;
	}

	/** Generates one section on the calling thread (diagnostics: not counted, cannot be cancelled). */
	public void generateNow(int originX, int originZ, byte detailLevel, IDhApiFullDataSource dataSource, boolean record) {
		this.generateNow(originX, originZ, detailLevel, dataSource, record, () -> false);
	}

	/**
	 * Generates one section on the calling thread.
	 *
	 * @param record false for diagnostics (the benchmark command), so they do not show up in the progress counters
	 * @param cancel checked between the parts of the work; the section stops with a {@link CancellationException}
	 */
	public void generateNow(int originX, int originZ, byte detailLevel, IDhApiFullDataSource dataSource, boolean record, BooleanSupplier cancel) {
		if (this.closed) throw new CancellationException("SeedGenGlobe generator closed");
		Worker worker = this.idle.poll();
		if (worker == null) worker = new Worker();
		java.util.Arrays.fill(worker.phases, 0L);
		boolean reuse = true;
		try {
			worker.sampler.phases = worker.phases;
			worker.sampler.sampleSection(originX, originZ, detailLevel, this.levelMinY, this.levelMaxY, this.topSkyLight, () -> this.closed || cancel.getAsBoolean());
			int errors = worker.sampler.lastErrors;
			if (this.closed || cancel.getAsBoolean()) throw new CancellationException();
			long start = System.nanoTime();
			worker.writer.writeSection(worker.sampler, dataSource, this.levelMinY);
			worker.phases[GenProgress.Phase.WRITING.ordinal()] += System.nanoTime() - start;
			if (record) {
				start = System.nanoTime();
				this.tellListener(originX, originZ, detailLevel, worker.sampler);
				worker.phases[GenProgress.Phase.MAP.ordinal()] += System.nanoTime() - start;
				int width = dataSource.getWidthInDataColumns();
				this.progress.recordSection(width * width, errors, worker.phases);
			}
		}
		catch (RuntimeException | Error throwable) {
			// a sampler that stopped half-way is in a consistent state, but one that failed in an unexpected way is not reused
			reuse = throwable instanceof CancellationException;
			throw throwable;
		}
		finally {
			worker.sampler.phases = null;
			if (reuse && !this.closed) this.idle.offer(worker);
		}
	}

	/**
	 * Computes a section for the Xaero LOD map alone (the caller's job is counted by the map's progress) and hands the
	 * columns to {@code use} while they are valid.
	 */
	public void sampleForMap(int originX, int originZ, int detail, BooleanSupplier cancel, Consumer<SampledColumns> use) {
		if (this.closed) throw new CancellationException("SeedGenGlobe generator closed");
		Worker worker = this.idle.poll();
		if (worker == null) worker = new Worker();
		boolean reuse = true;
		try {
			worker.sampler.phases = null;
			worker.sampler.sampleSection(originX, originZ, detail, this.levelMinY, this.levelMaxY, this.topSkyLight, () -> this.closed || cancel.getAsBoolean());
			if (this.closed || cancel.getAsBoolean()) throw new CancellationException();
			use.accept(worker.sampler);
		}
		catch (RuntimeException | Error throwable) {
			reuse = throwable instanceof CancellationException;
			throw throwable;
		}
		finally {
			if (reuse && !this.closed) this.idle.offer(worker);
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
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe [{}]: a section listener failed ({} times so far)", this.progress.name, failures, throwable);
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

	/** Runs once when the generator closes (the bridge forgets it). */
	void onClose(@Nullable Runnable action) {
		this.onClose = action;
	}

	@Override
	public void preGeneratorTaskStart() {}

	/**
	 * Stops the generator: waiting sections are dropped and running ones stop at their next check (within a fraction
	 * of a second). Called by Distant Horizons when it closes the level, and by SeedGenGlobe when the server stops.
	 */
	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		this.listener = null;
		this.idle.clear();
		if (this.structures != null) {
			// what structures cost this session, for comparing runs from the log
			try {
				if (this.progress.finished() > 0) {
					for (String line : this.structures.statusLines()) SeedGenGlobe.LOGGER.info("{}: {}", this.progress.title(), line);
				}
			}
			catch (RuntimeException | LinkageError error) {
				SeedGenGlobe.LOGGER.debug("SeedGenGlobe: no structure summary", error);
			}
			this.structures.close();
		}
		SectionScheduler.get().cancel(this);
		this.progress.close();
		Runnable action = this.onClose;
		this.onClose = null;
		if (action != null) {
			try {
				action.run();
			}
			catch (RuntimeException exception) {
				SeedGenGlobe.LOGGER.debug("SeedGenGlobe: closing {} failed", this.progress.name, exception);
			}
		}
	}

	/** Waits (up to the timeout) until none of this generator's sections run any more. */
	public boolean awaitStopped(long timeoutMillis) {
		return SectionScheduler.get().awaitIdle(this, timeoutMillis);
	}

	public boolean isClosed() {
		return this.closed;
	}
}
