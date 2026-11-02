package dev.seedgenglobe.dh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.terrain.ColumnProfile;
import dev.seedgenglobe.terrain.SectionSampler;

/**
 * Turns {@link ColumnProfile}s into Distant Horizons data points through DH's public API.
 * <p>
 * Uses {@link IDhApiFullDataSource#setApiDataPointColumn} rather than DH internals, so the same jar works across
 * Distant Horizons versions that keep API 4+. Wrapper objects are cached per block state and biome.
 * One writer per worker thread; the shared caches are concurrent.
 */
public final class DhWriter {

	/** Shared per level. */
	public static final class Wrappers {

		final IDhApiLevelWrapper level;
		final ConcurrentHashMap<BlockState, IDhApiBlockStateWrapper> blocks = new ConcurrentHashMap<>();
		final ConcurrentHashMap<Holder<Biome>, IDhApiBiomeWrapper> biomes = new ConcurrentHashMap<>();
		/** Used for columns without a biome. Distant Horizons cannot store a data point without one. */
		final @Nullable IDhApiBiomeWrapper fallbackBiome;

		public Wrappers(IDhApiLevelWrapper level, @Nullable Holder<Biome> fallbackBiome) {
			this.level = level;
			IDhApiBiomeWrapper fallback = null;
			if (fallbackBiome != null) {
				try {
					fallback = this.wrapBiome(fallbackBiome);
				}
				catch (RuntimeException exception) {
					SeedGenGlobe.LOGGER.debug("SeedGenGlobe: could not wrap the fallback biome {}", fallbackBiome, exception);
				}
			}
			if (fallback == null) {
				try {
					fallback = DhApi.Delayed.wrapperFactory.getBiomeWrapper("minecraft:plains", level);
				}
				catch (IOException | RuntimeException exception) {
					SeedGenGlobe.LOGGER.warn("SeedGenGlobe: no fallback biome for Distant Horizons; columns without a biome will be left empty.", exception);
				}
			}
			this.fallbackBiome = fallback;
		}

		IDhApiBlockStateWrapper wrapBlock(BlockState state) {
			IDhApiBlockStateWrapper wrapper = this.blocks.get(state);
			if (wrapper == null) {
				if (state.isAir()) {
					wrapper = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
				}
				else {
					wrapper = DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] { state }, this.level);
				}
				IDhApiBlockStateWrapper raced = this.blocks.putIfAbsent(state, wrapper);
				if (raced != null) wrapper = raced;
			}
			return wrapper;
		}

		IDhApiBiomeWrapper wrapBiome(Holder<Biome> biome) {
			IDhApiBiomeWrapper wrapper = this.biomes.get(biome);
			if (wrapper == null) {
				wrapper = DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[] { biome }, this.level);
				IDhApiBiomeWrapper raced = this.biomes.putIfAbsent(biome, wrapper);
				if (raced != null) wrapper = raced;
			}
			return wrapper;
		}
	}

	private final Wrappers wrappers;
	private final ArrayList<DhApiTerrainDataPoint> points = new ArrayList<>(32);
	private final IdentityHashMap<BlockState, IDhApiBlockStateWrapper> localBlocks = new IdentityHashMap<>();
	private final IdentityHashMap<Holder<Biome>, IDhApiBiomeWrapper> localBiomes = new IdentityHashMap<>();

	public DhWriter(Wrappers wrappers) {
		this.wrappers = wrappers;
	}

	/** Writes all 64 x 64 profiles of the sampler into the data source. */
	public void writeSection(SectionSampler sampler, IDhApiFullDataSource dataSource, int levelMinY) {
		int width = Math.min(dataSource.getWidthInDataColumns(), SectionSampler.WIDTH);
		for (int relX = 0; relX < width; relX++) {
			for (int relZ = 0; relZ < width; relZ++) {
				this.writeColumn(sampler.profile(relX, relZ), dataSource, relX, relZ, levelMinY);
			}
		}
	}

	public void writeColumn(ColumnProfile profile, IDhApiFullDataSource dataSource, int relX, int relZ, int levelMinY) {
		ArrayList<DhApiTerrainDataPoint> points = this.points;
		points.clear();
		IDhApiBiomeWrapper biome = this.biome(profile.biome);
		if (biome == null || profile.count == 0) return;
		for (int run = 0; run < profile.count; run++) {
			points.add(DhApiTerrainDataPoint.create(
				(byte)(0),
				profile.blockLight[run],
				profile.skyLight[run],
				profile.bottoms[run] - levelMinY,
				profile.tops[run] - levelMinY,
				this.block(profile.states[run]),
				biome
			));
		}
		dataSource.setApiDataPointColumn(relX, relZ, points);
		points.clear();
	}

	private IDhApiBlockStateWrapper block(BlockState state) {
		IDhApiBlockStateWrapper wrapper = this.localBlocks.get(state);
		if (wrapper == null) {
			wrapper = this.wrappers.wrapBlock(state);
			this.localBlocks.put(state, wrapper);
		}
		return wrapper;
	}

	private @Nullable IDhApiBiomeWrapper biome(@Nullable Holder<Biome> biome) {
		if (biome == null) return this.wrappers.fallbackBiome;
		IDhApiBiomeWrapper wrapper = this.localBiomes.get(biome);
		if (wrapper == null) {
			try {
				wrapper = this.wrappers.wrapBiome(biome);
			}
			catch (RuntimeException exception) {
				// a biome Distant Horizons cannot resolve (for example one only the server knows): use the fallback
				wrapper = this.wrappers.fallbackBiome;
				if (wrapper == null) return null;
			}
			this.localBiomes.put(biome, wrapper);
		}
		return wrapper;
	}
}
