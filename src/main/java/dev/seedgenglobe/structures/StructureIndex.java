package dev.seedgenglobe.structures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import builderb0y.bigglobe.columns.ScriptedColumnLookup;
import builderb0y.bigglobe.scripting.wrappers.StructureStartWrapper;
import builderb0y.bigglobe.structures.RawGenerationStructure;
import builderb0y.bigglobe.structures.SizedStructure;
import builderb0y.bigglobe.structures.management.FlatStructureLocator.ChunkSortedStructurePieces;
import builderb0y.bigglobe.structures.management.SmartStructurePlacement;
import builderb0y.bigglobe.structures.management.StructureLocator.SortedStructurePieces;

import dev.seedgenglobe.GlobeConfig;

/**
 * Where Big Globe's structures are, found the way its own structure locator finds them, but kept apart from it.
 * <p>
 * Big Globe asks every structure set's placement for the structure starts whose position lies in an area, then drops
 * the starts that collide with a bigger one (or that a collision overrider rules out). This index does the same in
 * tiles of 512 x 512 blocks: per tile and structure set, the starts are computed once, on whichever thread first needs
 * them, and kept for later sections and detail levels until the tile is among the least recently used. Script
 * placements (villages, obelisks, ...) are asked about each 64-block cell of a tile exactly as Big Globe asks them,
 * since a script may treat its area in any way; the vanilla placements only look at each chunk on its own, so they
 * are asked about the whole tile at once. Collisions are checked against every start whose position is close enough
 * for its pieces to reach, like Big Globe does.
 * <p>
 * Small structures are left out of coarse LODs: once {@link #DECIDE_AFTER} starts of an ordinary set have been seen,
 * the set is only looked up at detail levels whose LOD cells are at most half as wide as its largest start (see
 * {@link #sizeDetail}). Big Globe says how far each structure may reach, but for its scripted ones (houses, wells,
 * campfires) that is 8 chunks whatever their size, so the size comes from the starts themselves. In 1.3.0 every
 * ordinary set was looked up for every section up to the structure detail setting, which made structure lookup more
 * than half the time of a coarse section.
 */
final class StructureIndex {

	static final int TILE_SHIFT = 9;
	static final int TILE = 1 << TILE_SHIFT;
	/** Big Globe's structure cells (FlatStructureLocator.StructurePos): the areas it asks script placements about. */
	static final int CELL_SHIFT = 6;
	static final Candidate[] NONE = new Candidate[0];

	/**
	 * One structure set this index looks for.
	 *
	 * @param tier the coarsest tier of detail levels the set is generated at (see {@link LodStructures#tier})
	 */
	record Placement(int index, Holder<StructureSet> holder, String name, int radius, int tier, boolean perCell) {}

	/** One structure start found in a tile. */
	static final class Candidate {

		final StructureStartWrapper start;
		final ChunkSortedStructurePieces sorted;
		final BoundingBox box;
		final Placement placement;
		final Structure structure;
		/** The farthest its pieces may reach from its position, per Big Globe. */
		final int radius;
		final int step;
		final String id;
		final Identifier key;
		/** For ordering like Minecraft's identifiers: path first, then namespace. */
		final String path, namespace;
		final boolean raw;
		/** Which of the generator's raw column value overriders apply to it. */
		final int[] rawOverriders;
		/**
		 * Per detail level: 0 = not checked yet, 1 = survives the collision checks against the sets looked up at that
		 * level, -1 = removed by them. Racing checks come to the same answer.
		 */
		final byte[] survives = new byte[16];

		Candidate(StructureStartWrapper start, Placement placement, LodStructures owner) {
			this.start = start;
			this.sorted = new ChunkSortedStructurePieces(start);
			this.box = start.box();
			this.placement = placement;
			this.structure = start.start().getStructure();
			int radius;
			try {
				radius = ((SizedStructure)(start.originalStructure().value())).bigglobe_getMaxRadiusInBlocks();
			}
			catch (RuntimeException | LinkageError error) {
				radius = placement.radius();
			}
			this.radius = Math.max(0, Math.min(radius, placement.radius()));
			this.step = this.structure.step().ordinal();
			Identifier key = owner.structureRegistry.getKey(this.structure);
			if (key == null) key = start.originalID();
			this.key = key;
			this.id = key.toString();
			this.path = key.getPath();
			this.namespace = key.getNamespace();
			this.raw = this.structure instanceof RawGenerationStructure;
			this.rawOverriders = owner.overriders.rawColumnValues.getIndices(this.structure);
		}

		@Override
		public String toString() {
			return this.id + " at " + this.start.pos().getX() + ", " + this.start.pos().getY() + ", " + this.start.pos().getZ();
		}
	}

	/** Big Globe sorts structures for its overriders by generation step, then by identifier. Position breaks ties. */
	static final Comparator<Candidate> ORDER = Comparator
		.comparingInt((Candidate candidate) -> candidate.step)
		.thenComparing(candidate -> candidate.path)
		.thenComparing(candidate -> candidate.namespace)
		.thenComparingInt(candidate -> candidate.start.pos().getX())
		.thenComparingInt(candidate -> candidate.start.pos().getZ())
		.thenComparingInt(candidate -> candidate.start.pos().getY());

	private static final class Entry {
		final CompletableFuture<Candidate[]> result = new CompletableFuture<>();
		volatile long lastUse;
	}

	final LodStructures owner;
	final Placement[] all;
	/** The sets generated at each tier: all of them, the large ones, the giant ones. */
	final Placement[][] tiers = new Placement[LodStructures.TIERS][];
	private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
	private final AtomicLong clock = new AtomicLong();
	private final ReentrantLock evicting = new ReentrantLock();

	final AtomicLong computed = new AtomicLong(), found = new AtomicLong(), failures = new AtomicLong(), nanos = new AtomicLong();

	/** Starts of an ordinary set seen before its size decides the detail levels it is looked up at. */
	static final int DECIDE_AFTER = 32;
	/** Per set: tiles computed, time spent on them, starts found, and the widest start (blocks). */
	final AtomicLongArray setTiles, setNanos;
	final AtomicIntegerArray setStarts, setSpan;
	private final AtomicIntegerArray sizeLogged;

	StructureIndex(LodStructures owner, Placement[] all) {
		this.owner = owner;
		this.all = all;
		int sets = 0;
		for (Placement placement : all) sets = Math.max(sets, placement.index() + 1);
		this.setTiles = new AtomicLongArray(sets);
		this.setNanos = new AtomicLongArray(sets);
		this.setStarts = new AtomicIntegerArray(sets);
		this.setSpan = new AtomicIntegerArray(sets);
		this.sizeLogged = new AtomicIntegerArray(sets);
		for (int tier = 0; tier < LodStructures.TIERS; tier++) {
			List<Placement> placements = new ArrayList<>();
			for (Placement placement : all) {
				if (placement.tier() >= tier) placements.add(placement);
			}
			this.tiers[tier] = placements.toArray(new Placement[0]);
		}
	}

	static long key(int tileX, int tileZ, int placement) {
		return ((long)(tileX & 0x3FFFFF) << 32) | ((long)(tileZ & 0x3FFFFF) << 10) | (placement & 0x3FF);
	}

	int cachedEntries() {
		return this.entries.size();
	}

	/**
	 * The structure starts of one structure set whose position lies in a tile, before collision checks.
	 * Blocks while another thread computes them.
	 */
	Candidate[] tile(int tileX, int tileZ, Placement placement) {
		long key = key(tileX, tileZ, placement.index());
		Entry entry = this.entries.get(key);
		if (entry == null) {
			Entry created = new Entry();
			entry = this.entries.putIfAbsent(key, created);
			if (entry == null) {
				created.lastUse = this.clock.incrementAndGet();
				Candidate[] result = NONE;
				try {
					result = this.compute(tileX, tileZ, placement);
				}
				finally {
					created.result.complete(result);
				}
				this.maybeEvict();
				return result;
			}
		}
		entry.lastUse = this.clock.incrementAndGet();
		return entry.result.join();
	}

	private Candidate[] compute(int tileX, int tileZ, Placement placement) {
		long start = System.nanoTime();
		// one column cache per tile: script placements look at the same few columns (region centres) from every cell
		ScriptedColumnLookup columns = this.owner.locatorContext.columnSource().lookup();
		List<Candidate> found = new ArrayList<>();
		int minX = tileX << TILE_SHIFT;
		int minZ = tileZ << TILE_SHIFT;
		if (placement.perCell()) {
			int cells = TILE >> CELL_SHIFT;
			for (int cellZ = 0; cellZ < cells; cellZ++) {
				for (int cellX = 0; cellX < cells; cellX++) {
					this.generate(placement, minX + (cellX << CELL_SHIFT), minZ + (cellZ << CELL_SHIFT), 1 << CELL_SHIFT, columns, found);
				}
			}
		}
		else {
			this.generate(placement, minX, minZ, TILE, columns, found);
		}
		long spent = System.nanoTime() - start;
		this.computed.incrementAndGet();
		this.found.addAndGet(found.size());
		this.nanos.addAndGet(spent);
		int set = placement.index();
		this.setTiles.incrementAndGet(set);
		this.setNanos.addAndGet(set, spent);
		if (!found.isEmpty()) {
			int widest = 0;
			for (Candidate candidate : found) widest = Math.max(widest, span(candidate.box));
			int span = widest;
			this.setSpan.accumulateAndGet(set, span, Math::max);
			int seen = this.setStarts.addAndGet(set, found.size());
			if (placement.tier() == 0 && seen >= DECIDE_AFTER && this.sizeLogged.compareAndSet(set, 0, 1)) {
				int detail = this.sizeDetail(placement);
				if (detail < this.owner.fullDetail) {
					this.owner.logSizeDecision(placement.name(), seen, this.setSpan.get(set), detail);
				}
			}
		}
		return found.isEmpty() ? NONE : found.toArray(NONE);
	}

	/** Width of a structure start in blocks: the larger side of its bounding box. */
	static int span(BoundingBox box) {
		return Math.max(box.maxX() - box.minX() + 1, box.maxZ() - box.minZ() + 1);
	}

	/**
	 * The coarsest detail level a structure this wide shows at: LOD cells (2^detail blocks) at most half its width,
	 * so it covers two cells or more. 16 blocks: 3; 32: 4; 64: 5.
	 */
	static int detailForSpan(int span) {
		int log = 31 - Integer.numberOfLeadingZeros(Math.max(1, span));
		return Math.max(0, log - 1);
	}

	/**
	 * The coarsest detail level an ordinary set is looked up at, from the size of its starts so far; unlimited
	 * (the structure detail setting decides) until {@link #DECIDE_AFTER} starts were seen, and for large and giant sets.
	 */
	int sizeDetail(Placement placement) {
		if (placement.tier() > 0) return Integer.MAX_VALUE;
		int set = placement.index();
		if (this.setStarts.get(set) < DECIDE_AFTER) return Integer.MAX_VALUE;
		return detailForSpan(this.setSpan.get(set));
	}

	/** Whether a set is looked up for sections of this detail level. */
	boolean included(Placement placement, int detail) {
		return placement.tier() >= this.owner.tier(detail) && detail <= this.sizeDetail(placement);
	}

	private void generate(Placement placement, int minX, int minZ, int size, ScriptedColumnLookup columns, List<Candidate> found) {
		BoundingBox area = new BoundingBox(minX, this.owner.levelMinY, minZ, minX + size - 1, this.owner.levelMaxY - 1, minZ + size - 1);
		try {
			((SmartStructurePlacement)(((StructureSet)(placement.holder().value())).placement()))
				.bigglobe_generateStructuresInArea(
					new SmartStructurePlacement.Context(
						this.owner.locator,
						this.owner.locatorContext,
						placement.holder(),
						columns,
						area,
						true
					)
				)
				.forEach(start -> found.add(new Candidate(start, placement, this.owner)));
		}
		catch (RuntimeException | StackOverflowError | LinkageError error) {
			this.failures.incrementAndGet();
			this.owner.reportFailure("finding " + placement.name() + " structures at " + minX + ", " + minZ, error);
		}
	}

	/** Tiles kept: the memory load preset's number. */
	static int maxEntries() {
		return GlobeConfig.get().memoryLoad.structureTiles();
	}

	private void maybeEvict() {
		if (this.entries.size() <= maxEntries() || !this.evicting.tryLock()) return;
		try {
			long[] uses = this.entries.values().stream().filter(entry -> entry.result.isDone()).mapToLong(entry -> entry.lastUse).sorted().toArray();
			if (uses.length == 0) return;
			long threshold = uses[uses.length / 4];
			this.entries.values().removeIf(entry -> entry.result.isDone() && entry.lastUse <= threshold);
		}
		finally {
			this.evicting.unlock();
		}
	}

	/** Whether a start survives Big Globe's collision checks against the structure sets looked up at a detail level. */
	boolean survives(Candidate candidate, int detail) {
		int slot = Math.max(0, Math.min(candidate.survives.length - 1, detail));
		int known = candidate.survives[slot];
		if (known != 0) return known > 0;
		boolean survives = this.checkCollisions(candidate, detail);
		candidate.survives[slot] = (byte)(survives ? 1 : -1);
		return survives;
	}

	private boolean checkCollisions(Candidate candidate, int detail) {
		BoundingBox box = candidate.box;
		ScriptedColumnLookup columns = null;
		for (Placement placement : this.tiers[this.owner.tier(detail)]) {
			if (!this.included(placement, detail)) continue;
			int reach = placement.radius();
			int minTileX = (box.minX() - reach) >> TILE_SHIFT, maxTileX = (box.maxX() + reach) >> TILE_SHIFT;
			int minTileZ = (box.minZ() - reach) >> TILE_SHIFT, maxTileZ = (box.maxZ() + reach) >> TILE_SHIFT;
			for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
				for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
					for (Candidate other : this.tile(tileX, tileZ, placement)) {
						if (other == candidate || other.sorted.equals(candidate.sorted)) continue;
						if (!SortedStructurePieces.intersects(candidate.sorted, other.sorted)) continue;
						if (columns == null) columns = this.owner.locatorContext.columnSource().lookup();
						int priority;
						try {
							priority = this.owner.overriders.getCollisionPriority(columns, candidate.start, other.start);
						}
						catch (RuntimeException | StackOverflowError | LinkageError error) {
							this.owner.reportFailure("checking " + candidate + " against " + other, error);
							priority = 0;
						}
						if (priority < 0 || (priority == 0 && candidate.sorted.volume <= other.sorted.volume)) return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * The surviving starts whose bounding box intersects an area (inclusive block coordinates), sorted the way Big
	 * Globe sorts them for its overriders.
	 *
	 * @param grow also take starts whose box is within their own radius of the area: the ones Big Globe hands to its
	 * column value overriders for the columns there
	 */
	List<Candidate> near(int minX, int minZ, int maxX, int maxZ, int detail, boolean grow) {
		return this.near(minX, minZ, maxX, maxZ, detail, grow, () -> false);
	}

	/**
	 * As above, for the structure sets a section of this detail level gets, checking {@code cancel} before every tile
	 * (a tile can take a while to compute the first time).
	 */
	List<Candidate> near(int minX, int minZ, int maxX, int maxZ, int detail, boolean grow, BooleanSupplier cancel) {
		List<Candidate> result = new ArrayList<>();
		for (Placement placement : this.tiers[this.owner.tier(detail)]) {
			if (!this.included(placement, detail)) continue;
			int reach = grow ? placement.radius() * 2 : placement.radius();
			int minTileX = (minX - reach) >> TILE_SHIFT, maxTileX = (maxX + reach) >> TILE_SHIFT;
			int minTileZ = (minZ - reach) >> TILE_SHIFT, maxTileZ = (maxZ + reach) >> TILE_SHIFT;
			for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
				for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
					if (cancel.getAsBoolean()) throw new CancellationException("SeedGenGlobe section no longer wanted");
					for (Candidate candidate : this.tile(tileX, tileZ, placement)) {
						int margin = grow ? candidate.radius : 0;
						if (!candidate.box.intersects(minX - margin, minZ - margin, maxX + margin, maxZ + margin)) continue;
						if (!this.survives(candidate, detail)) continue;
						result.add(candidate);
					}
				}
			}
		}
		result.sort(ORDER);
		return result;
	}

	@Nullable Placement placement(int index) {
		for (Placement placement : this.all) {
			if (placement.index() == index) return placement;
		}
		return null;
	}
}
