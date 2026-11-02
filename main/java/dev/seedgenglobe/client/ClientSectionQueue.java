package dev.seedgenglobe.client;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.pos.DhSectionPos;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.dh.GlobeWorldGenerator;
import dev.seedgenglobe.gen.Focus;
import dev.seedgenglobe.gen.GenProgress;
import dev.seedgenglobe.gen.SectionScheduler;

/**
 * The LOD sections a multiplayer client's Distant Horizons asks for, answered by local generation. Each request is a
 * job on the shared {@link SectionScheduler}, which runs the ones nearest the player first, like the server's.
 */
final class ClientSectionQueue {

	final GlobeWorldGenerator generator;
	private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
	private volatile boolean closed;

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
	}

	/** How many requests Distant Horizons may have open at once: what SeedGenGlobe's threads can take. */
	int maxRunning() {
		return SectionScheduler.get().capacity();
	}

	boolean isClosed() {
		return this.closed;
	}

	/** Distant Horizons' camera position: the point generation works outward from. */
	void setTarget(int x, int z) {
		Focus.set(this.generator.dimensionId, new int[] { x, z });
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
		boolean[] created = new boolean[1];
		Task task = this.tasks.compute(pos, (key, existing) -> {
			if (existing == null || existing.future.isDone()) {
				created[0] = true;
				return new Task(key);
			}
			return existing;
		});
		if (created[0]) this.schedule(task);
		return task.future;
	}

	private void schedule(Task task) {
		long pos = task.pos;
		byte sectionDetail = DhSectionPos.getDetailLevel(pos);
		int detail = sectionDetail - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		int minX = DhSectionPos.getMinCornerBlockX(pos), minZ = DhSectionPos.getMinCornerBlockZ(pos);
		SectionScheduler.get().submit(new SectionScheduler.Job(SectionScheduler.Kind.CLIENT, this.generator, this.generator.dimensionId, minX, minZ, detail) {

			@Override
			public boolean cancelled() {
				return task.future.isDone() || ClientSectionQueue.this.closed || ClientSectionQueue.this.generator.isClosed();
			}

			@Override
			protected void execute() {
				task.started = true;
				FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos);
				try {
					// same propagation flags Distant Horizons' own world generation queue uses, except that generated
					// sections do not rewrite their parents (see SectionFlags: that is what leaves holes while LODs build)
					dataSource.applyToChildren = sectionDetail > DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
					dataSource.applyToParent = GlobeConfig.get().updateParentSections && sectionDetail < DhSectionPos.SECTION_REGION_DETAIL_LEVEL;
					ClientSectionQueue.this.generator.generateNow(minX, minZ, (byte)(detail), dataSource, true, this::cancelled);
					if (task.future.complete(DataSourceRetrievalResult.CreateSuccess(pos, dataSource))) dataSource = null;
				}
				finally {
					if (dataSource != null) close(dataSource);
					ClientSectionQueue.this.tasks.remove(pos, task);
				}
			}

			@Override
			protected void dropped() {
				ClientSectionQueue.this.tasks.remove(pos, task);
				task.future.cancel(false);
			}

			@Override
			protected void failed(Throwable throwable) {
				ClientSectionQueue.this.tasks.remove(pos, task);
				if (!(throwable instanceof CancellationException)) SeedGenGlobe.LOGGER.debug("SeedGenGlobe: a client LOD section failed", throwable);
				task.future.completeExceptionally(throwable);
			}

			@Override
			protected GenProgress progress() {
				return ClientSectionQueue.this.generator.progress;
			}
		});
	}

	private static void close(FullDataSourceV2 dataSource) {
		try {
			dataSource.close();
		}
		catch (Throwable ignored) {}
	}

	int waitingCount() {
		int waiting = 0;
		for (Task task : this.tasks.values()) {
			if (!task.started && !task.future.isDone()) waiting++;
		}
		return waiting;
	}

	int runningCount() {
		int running = 0;
		for (Task task : this.tasks.values()) {
			if (task.started && !task.future.isDone()) running++;
		}
		return running;
	}

	void removeIf(DhSectionPos.ICancelablePrimitiveLongConsumer predicate) {
		for (Task task : this.tasks.values()) {
			if (!task.started && predicate.accept(task.pos)) {
				// the scheduler drops the job once it sees the future is done
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
	}
}
