package dev.seedgenglobe.terrain;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import builderb0y.bigglobe.ClientState.ClientGeneratorParams;
import builderb0y.bigglobe.chunkgen.BigGlobeScriptedChunkGenerator;
import builderb0y.bigglobe.chunkgen.ScriptedColumnBiomeSource;
import builderb0y.bigglobe.chunkgen.scripted.TerrainLayer;
import builderb0y.bigglobe.columns.ColumnEntryRegistry;
import builderb0y.bigglobe.columns.ColumnScript.ColumnYToBiomeScript;
import builderb0y.bigglobe.columns.ScriptedColumn;

/**
 * Everything needed to compute Big Globe terrain columns for one dimension.
 * <p>
 * On a server (or in singleplayer) it comes straight from the dimension's {@link BigGlobeScriptedChunkGenerator}.
 * On a multiplayer client it comes from the {@link ClientGeneratorParams} that Big Globe itself syncs to players:
 * the same terrain layer, column scripts and (already hashed) column seed, so the client can compute the same
 * terrain without ever learning the world seed.
 */
public final class GlobeTerrain {

	public final String dimension;
	public final ScriptedColumn.Factory columnFactory;
	public final ScriptedColumn.WorldInfo worldInfo;
	public final TerrainLayer layer;
	public final @Nullable ColumnYToBiomeScript.Catcher biomeScript;
	public final ColumnEntryRegistry registry;
	/** Vertical range of the generator, minY inclusive, maxY exclusive. */
	public final int minY, maxY;
	public final int seaLevel;
	public final boolean clientSide;
	/** Used when a column's biome cannot be determined. */
	public final @Nullable Holder<Biome> fallbackBiome;

	private GlobeTerrain(
		String dimension,
		ScriptedColumn.Factory columnFactory,
		ScriptedColumn.WorldInfo worldInfo,
		TerrainLayer layer,
		@Nullable ColumnYToBiomeScript.Catcher biomeScript,
		ColumnEntryRegistry registry,
		int minY,
		int maxY,
		int seaLevel,
		boolean clientSide,
		@Nullable Holder<Biome> fallbackBiome
	) {
		this.dimension = dimension;
		this.columnFactory = columnFactory;
		this.worldInfo = worldInfo;
		this.layer = layer;
		this.biomeScript = biomeScript;
		this.registry = registry;
		this.minY = minY;
		this.maxY = maxY;
		this.seaLevel = seaLevel;
		this.clientSide = clientSide;
		this.fallbackBiome = fallbackBiome;
	}

	/** @return null if the generator has not finished compiling its scripts yet. */
	public static @Nullable GlobeTerrain fromGenerator(BigGlobeScriptedChunkGenerator generator, String dimension, @Nullable Holder<Biome> fallbackBiome) {
		ColumnEntryRegistry registry = generator.columnEntryRegistry;
		if (registry == null || registry.columnFactory == null || generator.compiledWorldTraits == null || generator.layer == null) {
			return null;
		}
		ColumnYToBiomeScript.Catcher biomeScript = null;
		if (generator.biome_source() instanceof ScriptedColumnBiomeSource source) {
			biomeScript = source.script;
		}
		return new GlobeTerrain(
			dimension,
			registry.columnFactory,
			new ScriptedColumn.WorldInfo(generator),
			(TerrainLayer)(generator.layer.value()),
			biomeScript,
			registry,
			generator.height.min_y(),
			generator.height.max_y(),
			generator.getSeaLevel(),
			false,
			fallbackBiome
		);
	}

	/** @return null if the server did not sync terrain layers (Big Globe only does when its own LOD renderer could use them). */
	public static @Nullable GlobeTerrain fromClient(@Nullable ClientGeneratorParams params, String dimension, @Nullable Holder<Biome> fallbackBiome) {
		if (params == null || params.layer == null || params.columnEntryRegistry == null || params.compiledWorldTraits == null) {
			return null;
		}
		ColumnEntryRegistry registry = params.columnEntryRegistry;
		if (registry.columnFactory == null) return null;
		return new GlobeTerrain(
			dimension,
			registry.columnFactory,
			params.worldInfo(),
			(TerrainLayer)(params.layer.value()),
			params.biomeSource != null ? params.biomeSource.script : null,
			registry,
			params.minY,
			params.maxY,
			params.seaLevel != null ? params.seaLevel.intValue() : params.minY,
			true,
			fallbackBiome
		);
	}

	public ScriptedColumn newColumn(ScriptedColumn.Params params) {
		return this.columnFactory.create(params);
	}

	@Override
	public String toString() {
		return this.dimension + " [y " + this.minY + ".." + this.maxY + ", sea " + this.seaLevel + (this.clientSide ? ", client" : ", server") + ']';
	}
}
