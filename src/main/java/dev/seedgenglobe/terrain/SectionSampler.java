package dev.seedgenglobe.terrain;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import builderb0y.bigglobe.chunkgen.QuadHolder;
import builderb0y.bigglobe.chunkgen.QuadHolder.QuadColumn;
import builderb0y.bigglobe.chunkgen.QuadHolder.QuadList;
import builderb0y.bigglobe.columns.ScriptedColumn;
import builderb0y.bigglobe.columns.ScriptedColumn.ColumnUsage;
import builderb0y.bigglobe.columns.ScriptedColumn.Hints;
import builderb0y.bigglobe.columns.ScriptedColumn.UndergroundMode;
import builderb0y.bigglobe.scripting.wrappers.entries.BiomeEntry;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.gen.GenProgress.Phase;
import dev.seedgenglobe.structures.LodStructures;
import dev.seedgenglobe.structures.StructureFrame;

/**
 * Computes one Distant Horizons section (64 x 64 LOD columns) of Big Globe terrain.
 * <p>
 * Columns are evaluated two by two, the same way Big Globe's chunk generator does it, because surface scripts
 * look at the neighbouring columns of their quad (for slopes and similar). At detail level n the four columns
 * of a quad are 2^n blocks apart and Big Globe's LOD hints tell its scripts so, which lets them skip work that
 * would never be visible at that scale.
 * <p>
 * Not thread safe: each worker thread borrows its own sampler.
 */
public final class SectionSampler implements SampledColumns {

	public static final int WIDTH = SampledColumns.WIDTH;

	public final GlobeTerrain terrain;
	private final QuadColumn quad;
	/**
	 * The four columns' block segments, reused for every quad (cleared, keeping their arrays) instead of four new
	 * lists per quad as in Big Globe's own generator: Minecraft 26.3 pools its terrain buffers the same way. Each
	 * profile copies what it needs before the next quad.
	 */
	private final QuadList lists = new QuadList();
	private final ColumnProfile[] profiles = new ColumnProfile[WIDTH * WIDTH];
	private final BlockState air = Blocks.AIR.defaultBlockState();
	private final @Nullable CanopyModel canopy;
	private final @Nullable GroundCover ground;
	private final @Nullable StructureFrame structures;
	private final UndergroundMode underground;
	private final boolean useBigGlobeUndergroundSetting;
	private final boolean sampleCenter;

	public int lastErrors;
	/**
	 * Where the time of each section goes, by {@link Phase} (nanoseconds, added to), or null to not time it.
	 * Set by the generator for the section it is about to sample.
	 */
	public long @Nullable [] phases;

	public SectionSampler(GlobeTerrain terrain, @Nullable CanopyModel.Shared canopy, @Nullable GroundCover.Shared ground, @Nullable LodStructures structures) {
		this.terrain = terrain;
		GlobeConfig config = GlobeConfig.get();
		ScriptedColumn.Params params = new ScriptedColumn.Params(0, 0, terrain.worldInfo, hints(0, UndergroundMode.FILL));
		this.quad = new QuadColumn(
			terrain.newColumn(params),
			terrain.newColumn(params),
			terrain.newColumn(params),
			terrain.newColumn(params)
		);
		for (int index = 0; index < this.profiles.length; index++) {
			this.profiles[index] = new ColumnProfile();
		}
		this.canopy = canopy != null ? new CanopyModel(canopy, this) : null;
		this.ground = ground != null ? new GroundCover(ground, this) : null;
		this.useBigGlobeUndergroundSetting = config.undergroundMode == GlobeConfig.UndergroundMode.BIGGLOBE;
		this.underground = switch (config.undergroundMode) {
			case NONE -> UndergroundMode.NONE;
			case CARVE -> UndergroundMode.CARVE;
			case DECORATE -> UndergroundMode.DECORATE;
			default -> UndergroundMode.FILL;
		};
		this.sampleCenter = config.sampleCellCenter;
		this.structures = structures != null ? structures.newFrame(this) : null;
	}

	private static Hints hints(int detail, UndergroundMode underground) {
		return new Hints(true, underground, detail, ColumnUsage.RAW_GENERATION);
	}

	private Hints hintsFor(int detail) {
		return this.useBigGlobeUndergroundSetting ? ColumnUsage.RAW_GENERATION.dhHints(detail) : hints(detail, this.underground);
	}

	@Override
	public ColumnProfile profile(int relX, int relZ) {
		return this.profiles[relX * WIDTH + relZ];
	}

	/** Block x of the sample point of LOD column relX. */
	public int sampleX(int originX, int relX, int detail) {
		return originX + (relX << detail) + this.halfCell(detail);
	}

	@Override
	public int halfCell(int detail) {
		return this.sampleCenter && detail > 0 ? 1 << (detail - 1) : 0;
	}

	/**
	 * Fills all 64 x 64 profiles of the section whose minimum corner is (originX, originZ).
	 *
	 * @param levelMinY lowest block of the level (inclusive)
	 * @param levelMaxY top of the level (exclusive)
	 */
	public void sampleSection(int originX, int originZ, int detail, int levelMinY, int levelMaxY, byte topSkyLight) {
		this.sampleSection(originX, originZ, detail, levelMinY, levelMaxY, topSkyLight, () -> false);
	}

	private void time(Phase phase, long since) {
		long[] phases = this.phases;
		if (phases != null) phases[phase.ordinal()] += System.nanoTime() - since;
	}

	private static void check(BooleanSupplier cancel) {
		if (cancel.getAsBoolean()) throw new CancellationException("SeedGenGlobe section no longer wanted");
	}

	/**
	 * Fills all 64 x 64 profiles of the section whose minimum corner is (originX, originZ), checking {@code cancel}
	 * before each part of the work and after every row of columns: when it says stop, the section is abandoned with a
	 * {@link CancellationException} (a sixty-fourth of a section later at most), and the sampler stays usable.
	 */
	public void sampleSection(int originX, int originZ, int detail, int levelMinY, int levelMaxY, byte topSkyLight, BooleanSupplier cancel) {
		check(cancel);
		int step = 1 << detail;
		int half = this.halfCell(detail);
		ScriptedColumn.Params params = new ScriptedColumn.Params(0, 0, this.terrain.worldInfo, this.hintsFor(detail));
		int genMinY = this.terrain.minY;
		int genMaxY = this.terrain.maxY;
		this.lastErrors = 0;
		this.observeNanos = 0L;
		if (this.ground != null) this.ground.begin();
		boolean structures = false;
		if (this.structures != null) {
			long lookupStart = System.nanoTime();
			try {
				structures = this.structures.begin(originX, originZ, detail, levelMinY, levelMaxY, topSkyLight, cancel);
			}
			catch (CancellationException cancelled) {
				throw cancelled;
			}
			catch (Throwable throwable) {
				if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) {
					throw error;
				}
				check(cancel);
				this.lastErrors++;
				SeedGenGlobe.logColumnError(this.terrain, originX, originZ, throwable);
			}
			finally {
				this.time(Phase.STRUCTURE_LOOKUP, lookupStart);
			}
		}
		long terrainStart = System.nanoTime();
		long[] phases = this.phases;
		long excluded = 0L;
		for (int relZ = 0; relZ < WIDTH; relZ += 2) {
			if (cancel.getAsBoolean()) {
				if (phases != null) phases[Phase.TERRAIN.ordinal()] += System.nanoTime() - terrainStart - excluded;
				throw new CancellationException("SeedGenGlobe section no longer wanted");
			}
			for (int relX = 0; relX < WIDTH; relX += 2) {
				int x = originX + (relX << detail) + half;
				int z = originZ + (relZ << detail) + half;
				try {
					this.quad.at(params, x, z, step);
					if (structures) {
						long overrideStart = System.nanoTime();
						this.structures.override(this.quad, x, z, step);
						long spent = System.nanoTime() - overrideStart;
						excluded += spent;
						if (phases != null) phases[Phase.STRUCTURE_TERRAIN.ordinal()] += spent;
					}
					QuadList lists = this.emptyLists(genMinY, genMaxY);
					QuadHolder.generate(this.quad, lists, this.terrain.layer);
					this.finishColumn(this.quad.object00, lists, 0, relX,     relZ,     levelMinY, levelMaxY, topSkyLight, originX, originZ, detail);
					this.finishColumn(this.quad.object01, lists, 1, relX + 1, relZ,     levelMinY, levelMaxY, topSkyLight, originX, originZ, detail);
					this.finishColumn(this.quad.object10, lists, 2, relX,     relZ + 1, levelMinY, levelMaxY, topSkyLight, originX, originZ, detail);
					this.finishColumn(this.quad.object11, lists, 3, relX + 1, relZ + 1, levelMinY, levelMaxY, topSkyLight, originX, originZ, detail);
				}
				catch (Throwable throwable) {
					if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) {
						throw error;
					}
					// scripts fail once the server that owns their registries stops: that is the section being stopped
					check(cancel);
					this.lastErrors++;
					SeedGenGlobe.logColumnError(this.terrain, x, z, throwable);
					this.profile(relX,     relZ    ).fillAir(this.air, levelMinY, levelMaxY, topSkyLight);
					this.profile(relX + 1, relZ    ).fillAir(this.air, levelMinY, levelMaxY, topSkyLight);
					this.profile(relX,     relZ + 1).fillAir(this.air, levelMinY, levelMaxY, topSkyLight);
					this.profile(relX + 1, relZ + 1).fillAir(this.air, levelMinY, levelMaxY, topSkyLight);
				}
			}
		}
		excluded += this.observeNanos;
		this.observeNanos = 0L;
		if (phases != null) phases[Phase.TERRAIN.ordinal()] += System.nanoTime() - terrainStart - excluded;
		if (structures) {
			check(cancel);
			long placeStart = System.nanoTime();
			try {
				this.structures.place(cancel);
			}
			catch (CancellationException cancelled) {
				throw cancelled;
			}
			catch (Throwable throwable) {
				if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) {
					throw error;
				}
				check(cancel);
				this.lastErrors++;
				SeedGenGlobe.logColumnError(this.terrain, originX, originZ, throwable);
			}
			finally {
				this.time(Phase.STRUCTURES, placeStart);
			}
		}
		if (this.canopy != null) {
			check(cancel);
			long canopyStart = System.nanoTime();
			try {
				this.canopy.apply(originX, originZ, detail, levelMinY);
			}
			catch (Throwable throwable) {
				if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) {
					throw error;
				}
				check(cancel);
				this.lastErrors++;
				SeedGenGlobe.logColumnError(this.terrain, originX, originZ, throwable);
			}
			finally {
				this.time(Phase.TREES, canopyStart);
			}
		}
		if (this.ground != null) {
			check(cancel);
			long groundStart = System.nanoTime();
			try {
				this.ground.apply();
			}
			catch (Throwable throwable) {
				if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) {
					throw error;
				}
				check(cancel);
				this.lastErrors++;
				SeedGenGlobe.logColumnError(this.terrain, originX, originZ, throwable);
			}
			finally {
				this.time(Phase.GROUND_COVER, groundStart);
			}
		}
	}

	/** Time spent in tree and ground cover observers during the column loop, moved out of the terrain phase. */
	private long observeNanos;

	/**
	 * The reused segment lists, empty, for a generator height of [minY, maxY). A list holds nothing but its segments
	 * and its bounds, so clearing one leaves it exactly like a new one.
	 */
	private QuadList emptyLists(int minY, int maxY) {
		QuadList lists = this.lists;
		if (lists.object00 == null || lists.object00.minY() != minY || lists.object00.maxY() != maxY) {
			lists.createNew(minY, maxY);
		}
		else {
			lists.object00.clear();
			lists.object01.clear();
			lists.object10.clear();
			lists.object11.clear();
		}
		return lists;
	}

	/**
	 * Computes the single column at (x, z) at full resolution, without tree canopies.
	 * Used by the verification command; reuses (and clobbers) the profiles of the first quad.
	 */
	public ColumnProfile probe(int x, int z, int levelMinY, int levelMaxY, byte topSkyLight) {
		ScriptedColumn.Params params = new ScriptedColumn.Params(0, 0, this.terrain.worldInfo, this.hintsFor(0));
		this.quad.at(params, x, z, 1);
		QuadList lists = new QuadList();
		lists.createNew(this.terrain.minY, this.terrain.maxY);
		QuadHolder.generate(this.quad, lists, this.terrain.layer);
		ColumnProfile profile = this.profile(0, 0);
		profile.build(this.quad.object00, lists.object00, levelMinY, levelMaxY, topSkyLight, this.air);
		profile.biome = this.biomeAt(this.quad.object00, profile);
		return profile;
	}

	private void finishColumn(ScriptedColumn column, QuadList lists, int which, int relX, int relZ, int levelMinY, int levelMaxY, byte topSkyLight, int originX, int originZ, int detail) {
		ColumnProfile profile = this.profile(relX, relZ);
		profile.build(
			column,
			switch (which) {
				case 0 -> lists.object00;
				case 1 -> lists.object01;
				case 2 -> lists.object10;
				default -> lists.object11;
			},
			levelMinY,
			levelMaxY,
			topSkyLight,
			this.air
		);
		profile.biome = this.biomeAt(column, profile);
		long[] phases = this.phases;
		if (this.canopy != null) {
			long start = phases != null ? System.nanoTime() : 0L;
			this.canopy.observe(column, profile, relX, relZ);
			if (phases != null) {
				long spent = System.nanoTime() - start;
				phases[Phase.TREES.ordinal()] += spent;
				this.observeNanos += spent;
			}
		}
		if (this.ground != null) {
			long start = phases != null ? System.nanoTime() : 0L;
			try {
				this.ground.observe(column, profile, relX, relZ, originX + (relX << detail), originZ + (relZ << detail), detail);
			}
			catch (RuntimeException | StackOverflowError exception) {
				// a decorator script failed for this column: it simply gets no ground cover
				this.ground.forget(relX, relZ);
				this.lastErrors++;
				SeedGenGlobe.logColumnError(this.terrain, column.x(), column.z(), exception);
			}
			if (phases != null) {
				long spent = System.nanoTime() - start;
				phases[Phase.GROUND_COVER.ordinal()] += spent;
				this.observeNanos += spent;
			}
		}
	}

	private @Nullable Holder<Biome> biomeAt(ScriptedColumn column, ColumnProfile profile) {
		if (this.terrain.biomeScript != null) {
			int y = Math.max(this.terrain.minY, Math.min(this.terrain.maxY - 1, profile.surfaceTop - 1));
			try {
				BiomeEntry entry = this.terrain.biomeScript.get(column, y);
				if (entry != null && entry.entry != null) {
					@SuppressWarnings("unchecked")
					Holder<Biome> holder = (Holder<Biome>)(Object)(entry.entry);
					return holder;
				}
			}
			catch (RuntimeException exception) {
				SeedGenGlobe.logColumnError(this.terrain, column.x(), column.z(), exception);
			}
		}
		return this.terrain.fallbackBiome;
	}

	@Override
	public BlockState air() {
		return this.air;
	}

	/** Whether structure blocks cover the terrain of LOD column (relX, relZ) of the section last sampled. */
	public boolean occupied(int relX, int relZ) {
		return this.structures != null && this.structures.occupied(relX, relZ);
	}
}
