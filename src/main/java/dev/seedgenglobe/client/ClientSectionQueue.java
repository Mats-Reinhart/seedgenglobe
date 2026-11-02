package dev.seedgenglobe.client;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.dh.GeneratorThreads;
import dev.seedgenglobe.dh.GlobeWorldGenerator;

/**
 * Nearest-first queue of LOD sections to generate on a multiplayer client.
 * <p>
 * Work runs on {@link GeneratorThreads}, like the server's: SeedGenGlobe's own threads by default, or Distant
 * Horizons' world generation pool (with its CPU limits and its pause while the camera moves fast) when
 * {@code generatorThreads} is -1; only if that pool is missing too does the queue start threads of its own.
 */
final class ClientSectionQueue {

	private static final AtomicInteger THREAD_IDS = new AtomicInteger();

	final GlobeWorldGenerator generator;
	private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
	private final AtomicInteger running = new AtomicInteger();
	private final int maxRunning;
	private volatile int targetX, targetZ;
	private volatile boolean closed;
	private @Nullable ExecutorService fallbackExecutor;

	static final class Task {

		final long pos;
		final CompletableFuture<DataSourceRetrievalResult> future = new CompletableFuture<>();
		volatile boolean started;

		Task(long pos) {
			this.pos = pos;
		}
	}

	ClientSectionQueue(GlobeWorldGenerator generator) {
		this.generator = generator;
		this.maxRunning = threadBudget();
	}

	/**
	 * How many sections may generate at once. Defaults to Distant Horizons' own thread count, minus one when
	 * C2ME or ScalableLux are installed: on a client those mods keep worker threads busy with chunk loading and
	 * light updates near the player, and those matter more than distant terrain.
	 */
	static int threadBudget() {
		int configured = GlobeConfig.get().clientThreads;
		if (configured > 0) return configured;
		int dhThreads;
		try {
			Integer value = DhApi.Delayed.configs != null ? DhApi.Delayed.configs.multiThreading().threadCount().getValue() : null;
			dhThreads = value != null && value > 0 ? value : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
		}
		catch (RuntimeException exception) {
			dhThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
		}
		if ((ModCompat.C2ME || ModCompat.SCALABLELUX) && dhThreads > 1) dhThreads--;
		return Math.max(1, dhThreads);
	}

	int maxRunning() {
		return this.maxRunning;
	}

	boolean isClosed() {
		return this.closed;
	}

	void setTarget(int x, int z) {
		this.targetX = x;
		this.targetZ = z;
		this.pump();
	}

	CompletableFuture<DataSourceRetrievalResult> submit(long pos) {
		if (this.closed) {
			CompletableFuture<DataSourceRetrievalResult> future = new CompletableFuture<>();
			future.cancel(false);
			return future;
		}
		if (DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL > this.generator.getLargestDataDetailLevel()) {
			// coarser than maxDetailLevel allows: Distant Horizons asks for the four quarters instead, as with its own queue
			return CompletableFuture.completedFuture(DataSourceRetrievalResult.CreateSplit());
		}
		// a finished or cancelled task for the same position must not answer a new request
		Task task = this.tasks.compute(pos, (key, existing) -> existing == null || existing.future.isDone() ? new Task(key) : existing);
		this.pump();
		return task.future;
	}

	int waitingCount() {
		int waiting = 0;
		for (Task task : this.tasks.values()) {
			if (!task.started && !task.future.isDone()) waiting++;
		}
		return waiting;
	}

	int runningCount() {
		return this.running.get();
	}

	void removeIf(DhSectionPos.ICancelablePrimitiveLongConsumer predicate) {
		for (Task task : this.tasks.values()) {
			if (!task.started && predicate.accept(task.pos)) {
				if (this.tasks.remove(task.pos, task)) task.future.cancel(false);
			}
		}
	}

	void cancelAll() {
		for (Task task : this.tasks.values()) {
			task.future.cancel(false);
		}
		this.tasks.clear();
	}

	void close() {
		this.closed = true;
		this.cancelAll();
		ExecutorService fallback = this.fallbackExecutor;
		if (fallback != null) fallback.shutdownNow();
	}

	private Executor executor() {
		Executor own = GeneratorThreads.executor();
		if (own != null) return own;
		Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		if (executor != null) return executor;
		synchronized (this) {
			if (this.fallbackExecutor == null) {
				ThreadFactory factory = runnable -> {
					Thread thread = new Thread(runnable, "SeedGenGlobe Client Worker " + THREAD_IDS.incrementAndGet());
					thread.setDaemon(true);
					thread.setPriority(Thread.MIN_PRIORITY);
					return thread;
				};
				this.fallbackExecutor = Executors.newFixedThreadPool(this.maxRunning, factory);
			}
			return this.fallbackExecutor;
		}
	}

	/** Starts the nearest waiting sections until the thread budget is used. */
	private void pump() {
		while (true) {
			Task next;
			synchronized (this.tasks) {
				if (this.closed || this.running.get() >= this.maxRunning) return;
				next = this.nearestWaiting();
				if (next == null) return;
				next.started = true;
				this.running.incrementAndGet();
			}
			try {
				this.executor().execute(() -> this.run(next));
			}
			catch (RuntimeException rejected) {
				synchronized (this.tasks) {
					this.running.decrementAndGet();
					next.started = false;
				}
				return;
			}
		}
	}

	private @Nullable Task nearestWaiting() {
		Task best = null;
		long bestDistance = Long.MAX_VALUE;
		int x = this.targetX, z = this.targetZ;
		for (Task task : this.tasks.values()) {
			if (task.started) continue;
			if (task.future.isDone()) {
				// cancelled by Distant Horizons before it started; remove only this task, not a newer one for the same position
				this.tasks.remove(task.pos, task);
				continue;
			}
			long dx = DhSectionPos.getCenterBlockPosX(task.pos) - x;
			long dz = DhSectionPos.getCenterBlockPosZ(task.pos) - z;
			long distance = Math.max(Math.abs(dx), Math.abs(dz));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = task;
			}
		}
		return best;
	}

	private void run(Task task) {
		FullDataSourceV2 dataSource = null;
		try {
			if (task.future.isDone() || this.closed) return;
			long pos = task.pos;
			byte sectionDetail = DhSectionPos.getDetailLevel(pos);
			dataSource = FullDataSourceV2.createEmpty(pos);
			// same propagation flags Distant Horizons' own world generation queue uses, except that generated
			// sections do not rewrite their parents (see SectionFlags: that is what leaves holes while LODs build)
			dataSource.applyToChildren = sectionDetail > DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
			dataSource.applyToParent = GlobeConfig.get().updateParentSections && sectionDetail < DhSectionPos.SECTION_REGION_DETAIL_LEVEL;
			this.generator.generateNow(
				DhSectionPos.getMinCornerBlockX(pos),
				DhSectionPos.getMinCornerBlockZ(pos),
				(byte)(sectionDetail - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL),
				dataSource
			);
			if (!task.future.complete(DataSourceRetrievalResult.CreateSuccess(pos, dataSource))) {
				dataSource.close();
			}
			dataSource = null;
		}
		catch (Throwable throwable) {
			if (dataSource != null) {
				try {
					dataSource.close();
				}
				catch (Throwable ignored) {}
			}
			if (!(throwable instanceof java.util.concurrent.CancellationException)) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe failed to generate a client LOD section", throwable);
			}
			task.future.completeExceptionally(throwable);
		}
		finally {
			this.tasks.remove(task.pos, task);
			this.running.decrementAndGet();
			this.pump();
		}
	}
}
