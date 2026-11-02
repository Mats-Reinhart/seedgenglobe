package dev.seedgenglobe.structures;

import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.minecraft.world.level.levelgen.structure.Structure;

import builderb0y.bigglobe.scripting.wrappers.StructureStartWrapper;
import builderb0y.bigglobe.structures.management.StructureLocator;
import builderb0y.bigglobe.util.Streamable;

/**
 * The structure locator Big Globe's placement code reports back to while {@link StructureIndex} asks it for structure
 * starts. The only question placements ask it is {@link #maybeHasBiomes}: whether a structure's biomes could occur
 * near a chunk at all, which Big Globe answers from the set of every biome in a 64-block cell, at every height. That
 * set is far more expensive to compute than the check it saves, and it never changes the outcome: a start whose
 * position is in a suitable biome always passes it, and Big Globe checks the biome at that position afterwards
 * anyway. So the answer here is always yes.
 * <p>
 * Nothing is cached here, so SeedGenGlobe never fills Big Globe's own structure caches with LOD-only results.
 */
final class LodStructureLocator extends StructureLocator {

	private final StructureLocator real;

	LodStructureLocator(StructureLocator real) {
		this.real = real;
	}

	@Override
	public boolean maybeHasBiomes(BiomeParams params) {
		return true;
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
