package dev.seedgenglobe.structures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

import builderb0y.bigglobe.chunkgen.BigGlobeScriptedChunkGenerator;
import builderb0y.bigglobe.chunkgen.QuadHolder.QuadColumn;
import builderb0y.bigglobe.columns.ScriptedColumn.ColumnUsage;
import builderb0y.bigglobe.columns.ScriptedColumnLookup;
import builderb0y.bigglobe.mixins.StructureStart_ChildrenGetter;
import builderb0y.bigglobe.noise.MojangPermuter;
import builderb0y.bigglobe.noise.Permuter;
import builderb0y.bigglobe.overriders.ColumnValueOverrider;
import builderb0y.bigglobe.scripting.wrappers.StructureStartWrapper;
import builderb0y.bigglobe.scripting.wrappers.WorldWrapper;
import builderb0y.bigglobe.scripting.wrappers.WorldWrapper.AutoOverride;
import builderb0y.bigglobe.scripting.wrappers.WorldWrapper.Coordination;
import builderb0y.bigglobe.structures.ScriptStructures;
import builderb0y.bigglobe.structures.scripted.ScriptedStructure;
import builderb0y.bigglobe.structures.scripted.StructurePlacementScript;
import builderb0y.bigglobe.util.SymmetricOffset;
import builderb0y.bigglobe.util.WorldOrChunk;
import builderb0y.bigglobe.util.WorldUtil;

import dev.seedgenglobe.structures.StructureIndex.Candidate;
import dev.seedgenglobe.terrain.SectionSampler;

/**
 * Structures for the section one {@link SectionSampler} is generating, in the order Big Globe's chunk generator
 * deals with them:
 * <ol>
 *   <li>{@link #override}: while terrain columns are computed, the column value overriders of nearby structures
 *       reshape them (flattened ground under villages and houses, lake basins, the obelisks' plateaus);</li>
 *   <li>{@link #place}: raw-generation scripts run (the obelisk towers themselves), then every structure piece is
 *       placed, then each structure's after-placement step; the blocks go into the section's columns, which are
 *       relit, and the columns they cover are marked so tree canopies and ground cover keep off them.</li>
 * </ol>
 * At detail 0 pieces are placed chunk by chunk, exactly like in the game. At coarser detail only one block column
 * per LOD cell is kept, so Big Globe's script pieces are run for those columns alone, and other pieces for the
 * chunks that contain such a column.
 * <p>
 * Not thread safe: one per sampler.
 */
public final class StructureFrame {

	private static final long SEED_SALT = 0x5EED_6E0B_57C7_0001L;

	private final LodStructures owner;
	private final SectionSampler sampler;
	private final CaptureView view;
	private final CaptureLevel level;
	private final CaptureWorld rawWorld;
	private final boolean[] occupied = new boolean[CaptureView.COLUMNS];
	private boolean anyOccupied;

	private final Holder<ColumnValueOverrider.Entry>[] rawOverriders;
	private final List<StructureStartWrapper>[] lists;
	private final ScriptStructures[] structures;

	private List<Candidate> nearby = List.of();
	private final List<Candidate> inside = new ArrayList<>();
	private int originX, originZ, detail, span, levelMinY, levelMaxY, topSkyLight;
	private boolean active;
	/** Whether the section being generated is still wanted; checked between structures. */
	private BooleanSupplier cancel = () -> false;

	@SuppressWarnings("unchecked")
	StructureFrame(LodStructures owner, SectionSampler sampler) {
		this.owner = owner;
		this.sampler = sampler;
		this.view = new CaptureView(sampler);
		this.level = new CaptureLevel(owner, this.view);
		this.rawWorld = new CaptureWorld(this.view, this.level, owner.columnSeed);
		this.rawOverriders = owner.overriders.rawColumnValues.overriders();
		this.lists = new List[this.rawOverriders.length];
		for (int index = 0; index < this.lists.length; index++) this.lists[index] = new ArrayList<>();
		this.structures = new ScriptStructures[this.rawOverriders.length];
	}

	/**
	 * Looks up the structures near a section about to be sampled.
	 *
	 * @return whether there are any; if not, the other methods do nothing for this section
	 */
	public boolean begin(int originX, int originZ, int detail, int levelMinY, int levelMaxY, int topSkyLight, BooleanSupplier cancel) {
		this.cancel = cancel;
		if (this.anyOccupied) {
			Arrays.fill(this.occupied, false);
			this.anyOccupied = false;
		}
		this.inside.clear();
		this.nearby = List.of();
		this.originX = originX;
		this.originZ = originZ;
		this.detail = detail;
		this.span = SectionSampler.WIDTH << detail;
		this.levelMinY = levelMinY;
		this.levelMaxY = levelMaxY;
		this.topSkyLight = topSkyLight;
		int maxX = originX + this.span - 1, maxZ = originZ + this.span - 1;
		try {
			this.nearby = this.owner.index.near(originX, originZ, maxX, maxZ, this.owner.tier(detail), true, cancel);
		}
		catch (CancellationException cancelled) {
			throw cancelled;
		}
		catch (RuntimeException | StackOverflowError | LinkageError error) {
			this.stopIfCancelled();
			this.owner.reportFailure("looking up structures near " + originX + ", " + originZ, error);
		}
		for (Candidate candidate : this.nearby) {
			if (candidate.box.intersects(originX, originZ, maxX, maxZ)) this.inside.add(candidate);
		}
		this.active = !this.nearby.isEmpty();
		if (this.active) this.owner.sections.incrementAndGet();
		return this.active;
	}

	/** Whether structure blocks cover the terrain of LOD column (relX, relZ) of the current section. */
	public boolean occupied(int relX, int relZ) {
		return this.anyOccupied && this.occupied[relX * SectionSampler.WIDTH + relZ];
	}

	// ------------------------------------------------------------------------------------------------- overriders

	/**
	 * Applies the raw column value overriders of the structures near a quad of columns that was just positioned,
	 * before its terrain is generated, the way Big Globe's chunk generator does for every chunk: first the values
	 * any overrider may change are computed, then each overrider gets the structures whose bounding box comes
	 * within their own radius of the chunk. Overriders without such structures are skipped (Big Globe's would loop
	 * over nothing), and quads without any are left alone, as Big Globe's own LOD generator leaves all columns.
	 *
	 * @param x the first column of the quad; the others are {@code step} blocks further in x and z
	 */
	public void override(QuadColumn quad, int x, int z, int step) {
		if (!this.active) return;
		int minX = x & ~15, minZ = z & ~15;
		int maxX = ((x + step) & ~15) | 15, maxZ = ((z + step) & ~15) | 15;
		boolean any = false;
		for (Candidate candidate : this.nearby) {
			if (candidate.rawOverriders.length == 0) continue;
			int radius = candidate.radius;
			if (!candidate.box.intersects(minX - radius, minZ - radius, maxX + radius, maxZ + radius)) continue;
			for (int index : candidate.rawOverriders) this.lists[index].add(candidate.start);
			any = true;
		}
		if (!any) return;
		long start = System.nanoTime();
		try {
			this.fillStructures();
			quad.preComputeRaw(this.owner.overriders);
			for (int index = 0; index < this.rawOverriders.length; index++) {
				if (this.lists[index].isEmpty()) continue;
				quad.override(((ColumnValueOverrider.Entry)(this.rawOverriders[index].value())).script, this.structures[index]);
			}
		}
		catch (RuntimeException | StackOverflowError | LinkageError error) {
			this.stopIfCancelled();
			this.owner.reportFailure("applying structure overriders at " + x + ", " + z, error);
		}
		finally {
			this.clearLists();
			this.owner.overriddenQuads.incrementAndGet();
			this.owner.overrideNanos.addAndGet(System.nanoTime() - start);
		}
	}

	private void fillStructures() {
		for (int index = 0; index < this.lists.length; index++) {
			List<StructureStartWrapper> list = this.lists[index];
			this.structures[index] = list.isEmpty() ? ScriptStructures.EMPTY_SCRIPT_STRUCTURES : new ScriptStructures(list.toArray(new StructureStartWrapper[0]));
		}
	}

	private void clearLists() {
		for (List<StructureStartWrapper> list : this.lists) list.clear();
		Arrays.fill(this.structures, null);
	}

	// ------------------------------------------------------------------------------------------------- placement

	/** Throws a {@link CancellationException} when the section is no longer wanted. */
	private void stopIfCancelled() {
		if (this.cancel.getAsBoolean()) throw new CancellationException("SeedGenGlobe section no longer wanted");
	}

	/**
	 * Places the structures that reach into the section, once all its terrain columns are done. While they are placed,
	 * {@link CaptureGuard} keeps their questions about other structures from reaching the real level.
	 */
	public void place(BooleanSupplier cancel) {
		this.cancel = cancel;
		if (this.inside.isEmpty()) return;
		long start = System.nanoTime();
		this.view.begin(this.originX, this.originZ, this.detail, this.levelMinY, this.levelMaxY);
		this.level.begin(Permuter.permute(this.owner.columnSeed ^ SEED_SALT, this.originX, this.originZ, this.detail));
		CaptureGuard.enter();
		try {
			this.placeRaw();
			this.placeFeatures();
			if (this.view.anyWrites()) {
				this.view.apply(this.topSkyLight, this.occupied);
				this.anyOccupied = true;
				this.owner.sectionsWithBlocks.incrementAndGet();
			}
		}
		finally {
			CaptureGuard.exit();
			this.view.end();
			this.level.end();
			this.owner.placeNanos.addAndGet(System.nanoTime() - start);
		}
	}

	private BoundingBox sectionBox() {
		return new BoundingBox(this.originX, this.levelMinY, this.originZ, this.originX + this.span - 1, this.levelMaxY - 1, this.originZ + this.span - 1);
	}

	/**
	 * Raw generation, as in Big Globe's noise step: inside a script environment whose columns get the raw column
	 * value overriders, each raw script piece runs for the parts of the section it covers.
	 */
	private void placeRaw() {
		boolean any = false;
		for (Candidate candidate : this.inside) {
			if (candidate.raw) {
				any = true;
				break;
			}
		}
		if (!any) return;
		BoundingBox area = this.sectionBox();
		WorldWrapper parent = new WorldWrapper(
			this.rawWorld,
			this.owner.generator,
			new Permuter(Permuter.permute(this.owner.columnSeed, this.originX >> 4, this.originZ >> 4)),
			new Coordination(SymmetricOffset.IDENTITY, area, area),
			ColumnUsage.RAW_GENERATION.dhHints(0)
		);
		// columns the scripts look at get the overriders of the structures around them, like the terrain did
		for (Candidate candidate : this.nearby) {
			for (int index : candidate.rawOverriders) this.lists[index].add(candidate.start);
		}
		this.fillStructures();
		List<ScriptStructures> structures = new ArrayList<>();
		List<Holder<ColumnValueOverrider.Entry>> overriders = new ArrayList<>();
		for (int index = 0; index < this.lists.length; index++) {
			if (this.lists[index].isEmpty()) continue;
			structures.add(this.structures[index]);
			overriders.add(this.rawOverriders[index]);
		}
		this.clearLists();
		@SuppressWarnings("unchecked")
		Holder<ColumnValueOverrider.Entry>[] overriderArray = overriders.toArray(new Holder[0]);
		parent.overriders = new AutoOverride(
			structures.toArray(new ScriptStructures[0]),
			overriderArray,
			this.owner.overriders.rawColumnValueDependencies,
			this.owner.overriders.rawWorldTraitDependencies
		);
		ScriptedColumnLookup.GLOBAL.run(parent, () -> {
			for (Candidate candidate : this.inside) {
				if (!candidate.raw) continue;
				this.stopIfCancelled();
				long structureSeed = BigGlobeScriptedChunkGenerator.getStructureSeed(this.owner.columnSeed, candidate.key, candidate.start);
				List<StructurePiece> pieces = candidate.start.start().getPieces();
				for (int pieceIndex = 0, pieceCount = pieces.size(); pieceIndex < pieceCount; pieceIndex++) {
					// Big Globe's other raw pieces (geodes, dungeons) are underground, which it leaves out of LODs
					if (!(pieces.get(pieceIndex) instanceof ScriptedStructure.Piece piece)) continue;
					StructurePlacementScript.Catcher script = ((ScriptedStructure.CombinedStructureScripts)(piece.placement.entry.value())).raw_placement();
					if (script == null) continue;
					if (!piece.getBoundingBox().intersects(this.originX, this.originZ, this.originX + this.span - 1, this.originZ + this.span - 1)) continue;
					this.owner.pieces.incrementAndGet();
					this.runRaw(candidate, piece, script, Permuter.permute(structureSeed, pieceIndex));
				}
			}
		});
	}

	private void runRaw(Candidate candidate, ScriptedStructure.Piece piece, StructurePlacementScript.Catcher script, long pieceSeed) {
		BoundingBox box = piece.getBoundingBox();
		BoundingBox original = piece.originalBoundingBox;
		int minX = original.minX(), minY = original.minY(), minZ = original.minZ();
		int maxX = original.maxX(), maxY = original.maxY(), maxZ = original.maxZ();
		int midX = (minX + maxX + 1) >> 1, midY = (minY + maxY + 1) >> 1, midZ = (minZ + maxZ + 1) >> 1;
		this.forEachCall(box, this.detail > 0, (call, chunk) -> {
			BoundingBox effective = intersection(box, call);
			if (effective == null) return true;
			WorldWrapper wrapper = new WorldWrapper(
				this.rawWorld,
				this.owner.generator,
				new Permuter(pieceSeed),
				new Coordination(piece.transformation, effective, WorldUtil.chunkBox(chunk, this.levelMinY, this.levelMaxY)),
				ColumnUsage.RAW_GENERATION.dhHints(0)
			);
			this.rawWorld.wrapper = wrapper;
			try {
				script.place(
					wrapper,
					minX, minY, minZ,
					maxX, maxY, maxZ,
					midX, midY, midZ,
					call.minX(), call.minY(), call.minZ(),
					call.maxX(), call.maxY(), call.maxZ(),
					piece
				);
				return true;
			}
			catch (RuntimeException | StackOverflowError | LinkageError error) {
				this.stopIfCancelled();
				this.owner.placementFailures.incrementAndGet();
				this.owner.reportFailure("placing raw piece of " + candidate, error);
				return false;
			}
			finally {
				this.rawWorld.wrapper = null;
			}
		});
	}

	/**
	 * Feature generation, as in Big Globe's decoration step: every piece of every structure, then its after-placement.
	 * Big Globe runs this without overriders on the columns its scripts look at; the script environment set up here
	 * only lets those scripts share their columns across the many small calls a coarse section makes.
	 */
	private void placeFeatures() {
		WorldGenLevel world = this.level.world();
		BoundingBox area = this.sectionBox();
		WorldWrapper parent = new WorldWrapper(
			new WorldOrChunk.WorldDelegator(world),
			this.owner.generator,
			new Permuter(Permuter.permute(this.owner.columnSeed ^ SEED_SALT, this.originX >> 4, this.originZ >> 4)),
			new Coordination(SymmetricOffset.IDENTITY, area, area),
			ColumnUsage.FEATURES.dhHints(0)
		);
		ScriptedColumnLookup.GLOBAL.run(parent, () -> this.placeFeatures(world));
	}

	private void placeFeatures(WorldGenLevel world) {
		BigGlobeScriptedChunkGenerator generator = this.owner.generator;
		for (Candidate candidate : this.inside) {
			this.stopIfCancelled();
			StructureStart start = candidate.start.start();
			List<StructurePiece> pieces = start.getPieces();
			if (pieces.isEmpty()) continue;
			long structureSeed = BigGlobeScriptedChunkGenerator.getStructureSeed(this.owner.columnSeed, candidate.start.originalID(), candidate.start);
			BoundingBox first = pieces.get(0).getBoundingBox();
			BlockPos pivot = new BlockPos((first.minX() + first.maxX() + 1) >> 1, first.minY(), (first.minZ() + first.maxZ() + 1) >> 1);
			// some vanilla pieces settle their height or placement settings on first use: one thread at a time per structure
			synchronized (start) {
				boolean scriptsOnly = true;
				for (int pieceIndex = 0, pieceCount = pieces.size(); pieceIndex < pieceCount; pieceIndex++) {
					StructurePiece piece = pieces.get(pieceIndex);
					boolean script = piece instanceof ScriptedStructure.Piece;
					if (!script) scriptsOnly = false;
					BoundingBox box = piece.getBoundingBox();
					if (!box.intersects(this.originX, this.originZ, this.originX + this.span - 1, this.originZ + this.span - 1)) continue;
					long pieceSeed = Permuter.permute(structureSeed, pieceIndex);
					this.owner.pieces.incrementAndGet();
					this.forEachCall(box, this.detail > 0 && script, (call, chunk) -> {
						try {
							piece.postProcess(world, null, generator, new MojangPermuter(pieceSeed), call, chunk, pivot);
							return true;
						}
						catch (RuntimeException | StackOverflowError | LinkageError error) {
							this.stopIfCancelled();
							this.owner.placementFailures.incrementAndGet();
							this.owner.reportFailure("placing piece " + piece.getClass().getSimpleName() + " of " + candidate, error);
							return false;
						}
					});
				}
				PiecesContainer children = ((StructureStart_ChildrenGetter)(Object)(start)).bigglobe_getChildren();
				this.forEachCall(candidate.box, this.detail > 0 && scriptsOnly, (call, chunk) -> {
					try {
						start.getStructure().afterPlace(world, null, generator, new MojangPermuter(structureSeed), call, chunk, children);
						return true;
					}
					catch (RuntimeException | StackOverflowError | LinkageError error) {
						this.stopIfCancelled();
						this.owner.placementFailures.incrementAndGet();
						this.owner.reportFailure("finishing " + candidate, error);
						return false;
					}
				});
			}
		}
	}

	@FunctionalInterface
	interface Call {

		/** @return false to stop calling for this piece */
		boolean run(BoundingBox box, ChunkPos chunk);
	}

	/**
	 * Calls a placement for the parts of {@code target} in this section that hold sample points: once per sample
	 * point with a one-column box, or once per chunk containing one with the chunk's box.
	 */
	void forEachCall(BoundingBox target, boolean perColumn, Call call) {
		SampleGrid grid = this.view.grid;
		int minCellX = grid.firstCellX(target.minX()), maxCellX = grid.lastCellX(target.maxX());
		int minCellZ = grid.firstCellZ(target.minZ()), maxCellZ = grid.lastCellZ(target.maxZ());
		if (minCellX > maxCellX || minCellZ > maxCellZ) return;
		int bottom = this.levelMinY, top = this.levelMaxY - 1;
		if (perColumn) {
			for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
				int x = grid.sampleX(cellX);
				for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
					int z = grid.sampleZ(cellZ);
					if (!call.run(new BoundingBox(x, bottom, z, x, top, z), new ChunkPos(x >> 4, z >> 4))) return;
				}
			}
			return;
		}
		int lastChunkX = Integer.MIN_VALUE;
		for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
			int chunkX = grid.sampleX(cellX) >> 4;
			if (chunkX == lastChunkX) continue;
			lastChunkX = chunkX;
			int lastChunkZ = Integer.MIN_VALUE;
			for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
				int chunkZ = grid.sampleZ(cellZ) >> 4;
				if (chunkZ == lastChunkZ) continue;
				lastChunkZ = chunkZ;
				ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
				if (!call.run(WorldUtil.chunkBox(chunk, bottom, this.levelMaxY), chunk)) return;
			}
		}
	}

	static @Nullable BoundingBox intersection(BoundingBox a, BoundingBox b) {
		int minX = Math.max(a.minX(), b.minX()), minY = Math.max(a.minY(), b.minY()), minZ = Math.max(a.minZ(), b.minZ());
		int maxX = Math.min(a.maxX(), b.maxX()), maxY = Math.min(a.maxY(), b.maxY()), maxZ = Math.min(a.maxZ(), b.maxZ());
		if (minX > maxX || minY > maxY || minZ > maxZ) return null;
		return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
