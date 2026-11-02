package dev.seedgenglobe.structures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

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
 */
final class StructureIndex {

	static final int TILE_SHIFT = 9;
	static final int TILE = 1 << TILE_SHIFT;
	/** Big Globe's structure cells (FlatStructureLocator.StructurePos): the areas it asks script placements about. */
	static final int CELL_SHIFT = 6;
	static final int MAX_ENTRIES = 16384;
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
		 * Per tier: 0 = not checked yet, 1 = survives the collision checks against the sets of that tier,
		 * -1 = removed by them. Racing checks come to the same answer.
		 */
		final int[] survives = new int[LodStructures.TIERS];

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

	StructureIndex(LodStructures owner, Placement[] all) {
		this.owner = owner;
		this.all = all;
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
		this.computed.incrementAndGet();
		this.found.addAndGet(found.size());
		this.nanos.addAndGet(System.nanoTime() - start);
		return found.isEmpty() ? NONE : found.toArray(NONE);
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

	private void maybeEvict() {
		if (this.entries.size() <= MAX_ENTRIES || !this.evicting.tryLock()) return;
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

	/** Whether a start survives Big Globe's collision checks against the structure sets of a tier. */
	boolean survives(Candidate candidate, int tier) {
		int known = candidate.survives[tier];
		if (known != 0) return known > 0;
		boolean survives = this.checkCollisions(candidate, this.tiers[tier]);
		candidate.survives[tier] = survives ? 1 : -1;
		return survives;
	}

	private boolean checkCollisions(Candidate candidate, Placement[] placements) {
		BoundingBox box = candidate.box;
		ScriptedColumnLookup columns = null;
		for (Placement placement : placements) {
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
	List<Candidate> near(int minX, int minZ, int maxX, int maxZ, int tier, boolean grow) {
		List<Candidate> result = new ArrayList<>();
		for (Placement placement : this.tiers[tier]) {
			int reach = grow ? placement.radius() * 2 : placement.radius();
			int minTileX = (minX - reach) >> TILE_SHIFT, maxTileX = (maxX + reach) >> TILE_SHIFT;
			int minTileZ = (minZ - reach) >> TILE_SHIFT, maxTileZ = (maxZ + reach) >> TILE_SHIFT;
			for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
				for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
					for (Candidate candidate : this.tile(tileX, tileZ, placement)) {
						int margin = grow ? candidate.radius : 0;
						if (!candidate.box.intersects(minX - margin, minZ - margin, maxX + margin, maxZ + margin)) continue;
						if (!this.survives(candidate, tier)) continue;
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
