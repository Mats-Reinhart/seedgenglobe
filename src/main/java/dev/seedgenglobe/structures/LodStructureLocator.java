package dev.seedgenglobe.structures;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.google.common.base.Predicates;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;

import builderb0y.bigglobe.chunkgen.BigGlobeScriptedChunkGenerator;
import builderb0y.bigglobe.scripting.wrappers.StructureStartWrapper;
import builderb0y.bigglobe.structures.DelegatingStructure;
import builderb0y.bigglobe.structures.management.StructureLocator;
import builderb0y.bigglobe.util.Streamable;

/**
 * The structure locator Big Globe's placement code reports back to while {@link StructureIndex} asks it for structure
 * starts. The only question placements ask it is {@link #maybeHasBiomes}: whether a structure's biomes could occur
 * near a chunk at all, which Big Globe answers from the set of every biome in a 64-block cell, at every height. That
 * set is far more expensive to compute than the check it saves, and it never changes the outcome: a start whose
 * position is in a suitable biome always passes it, and Big Globe checks the biome at that position afterwards
 * anyway. So the plain locator always says yes.
 * <p>
 * The {@linkplain #centreColumn centre column} one, used for ocean monuments only, looks at the one column where Big
 * Globe checks a monument's biome in the end, like the early biome checks Minecraft 26.3 added before structures work
 * out their heights and pieces ({@code couldValidBiomeExistOnTopOfChunkCenter}). A monument stands on the centre of its
 * chunk (its position is the chunk's middle block, its 58 x 58 footprint is around it) and Big Globe's structure
 * overriders can only move a structure up or down, so the biome Big Globe finally checks is the one of that column, at
 * the height the monument ends up at. When no height of the column, over the generator's height (the range Big Globe's
 * own check covers), has a biome the monument allows, it cannot be placed, and saying so here saves Minecraft's check
 * of the monument's surroundings: 225 biome columns for every candidate chunk, most of them on land.
 * <p>
 * Nothing is cached here, so SeedGenGlobe never fills Big Globe's own structure caches with LOD-only results.
 */
final class LodStructureLocator extends StructureLocator {

	private final StructureLocator real;
	private final boolean centreColumn;
	/** Centre column checks made, and the chunks they ruled out. */
	final AtomicLong checked = new AtomicLong(), rejected = new AtomicLong();

	/**
	 * @param centreColumn rule out chunks whose centre column has no allowed biome (only for structures that
	 * {@link #centredOnChunk} says are placed there)
	 */
	LodStructureLocator(StructureLocator real, boolean centreColumn) {
		this.real = real;
		this.centreColumn = centreColumn;
	}

	/**
	 * Whether Big Globe checks this structure's biome in the centre column of the chunk it is started in, whatever
	 * happens to it: Minecraft's ocean monument, also when wrapped by Big Globe's delegating structures (which hand
	 * the generation point through unchanged). Exactly that class, not a mod's subclass, which could place it anywhere.
	 */
	static boolean centredOnChunk(Holder<Structure> structure) {
		try {
			for (int depth = 0; depth < 16 && structure.value() instanceof DelegatingStructure delegating; depth++) {
				structure = delegating.delegate();
			}
			return structure.value().getClass() == OceanMonumentStructure.class;
		}
		catch (RuntimeException | LinkageError error) {
			return false;
		}
	}

	@Override
	public boolean maybeHasBiomes(BiomeParams params) {
		if (!this.centreColumn || params.predicate() == Predicates.<Holder<Biome>>alwaysTrue()) return true;
		try {
			BoundingBox area = params.area();
			// a chunk's box, as Big Globe's createIn passes it; anything else is left to Big Globe's own checks
			if (area.maxX() - area.minX() != 15 || area.maxZ() - area.minZ() != 15 || (area.minX() & 15) != 0 || (area.minZ() & 15) != 0) {
				return true;
			}
			BigGlobeScriptedChunkGenerator generator = params.chunkGenerator();
			return this.centreColumnMayHave(
				generator.biome_source(),
				params.structureState().randomState().sampler(),
				(area.minX() + 8) >> 2,
				(area.minZ() + 8) >> 2,
				generator.height.min_y() >> 2,
				(generator.height.max_y() - 1) >> 2,
				params.predicate()
			);
		}
		catch (RuntimeException | StackOverflowError | LinkageError error) {
			// Big Globe's own checks decide (and report what went wrong)
			return true;
		}
	}

	/** Top down: an ocean column has the monument's biome right at the top, a land column has to be looked at whole. */
	private boolean centreColumnMayHave(BiomeSource source, Climate.Sampler sampler, int quartX, int quartZ, int minQuartY, int maxQuartY, Predicate<Holder<Biome>> allowed) {
		this.checked.incrementAndGet();
		for (int quartY = maxQuartY; quartY >= minQuartY; quartY--) {
			if (allowed.test(source.getNoiseBiome(quartX, quartY, quartZ, sampler))) return true;
		}
		this.rejected.incrementAndGet();
		return false;
	}

	@Override
	public Streamable<Holder<Structure>> allStructures() {
		return this.real.allStructures();
	}

	@Override
	public Stream<StructureStartWrapper> getStructuresIntersecting(Params params) {
		return Stream.empty();
	}

	@Override
	public @Nullable WeightedList<SpawnerData> getMobSpawns(Context context, BlockPos blockPos, MobCategory group) {
		return null;
	}

	@Override
	public Stream<StructureStartWrapper> getStructuresInside(Params params) {
		return Stream.empty();
	}

	@Override
	public Stream<StructureStartWrapper> getStructuresNearby(Params params, BlockPos center) {
		return Stream.empty();
	}
}
