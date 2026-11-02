package dev.seedgenglobe.terrain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import builderb0y.bigglobe.columns.ScriptedColumn;
import builderb0y.bigglobe.columns.ScriptedColumn.ColumnValueInfo;
import builderb0y.bigglobe.columns.entries.ColumnEntry;
import builderb0y.bigglobe.columns.traits.WorldTrait;
import builderb0y.bigglobe.columns.traits.WorldTraits;
import builderb0y.bigglobe.columns.traits.WorldTraits.WorldTraitInfo;
import builderb0y.bigglobe.dynamicRegistries.WoodPalette.WoodPaletteType;
import builderb0y.bigglobe.scripting.wrappers.entries.WoodPaletteEntry;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * Lays approximate tree canopies over LOD terrain.
 * <p>
 * Big Globe places trees as features after terrain generation, so its terrain layers alone produce bare forests.
 * This model reads the same world traits Big Globe's tree dispatcher reads (foliage at the surface, the wood
 * palette, enabled decorations, rivers and beaches) and puts leaves of the right species where trees would grow,
 * with heights following the same foliage-driven height formulas as Big Globe's tree features.
 * <p>
 * Near the player (cells up to 4 blocks wide) individual trees are placed on an 8 block jittered grid, matching
 * Big Globe's density of four tree attempts per chunk. Further out every LOD cell gets canopy with the probability
 * that a real forest of that density covers it. Real chunks replace all of this when the player gets close.
 */
public final class CanopyModel {

	static final int GRID = 8;
	static final double SUCCESS = 0.8D;
	static final int EXPLICIT_MAX_DETAIL = 2;

	static final byte NONE = 0, SPLIT = 1, CONE = 2, NARROW = 3, TALL = 4, FLAT = 5;

	/** Lookups shared by all workers of one terrain. Immutable after creation apart from the palette cache. */
	public static final class Shared {

		final GlobeTerrain terrain;
		final WorldTraits traits;
		final WorldTraitInfo foliage;
		final WorldTraitInfo palette;
		final @Nullable WorldTraitInfo decorations;
		final @Nullable WorldTraitInfo inRiver;
		final @Nullable WorldTraitInfo exactSurfaceY;
		final @Nullable ColumnValueInfo surfaceOverride;
		final int seaLevel;
		final long seed;
		final ConcurrentHashMap<Object, PaletteInfo> palettes = new ConcurrentHashMap<>();
		final ConcurrentHashMap<Class<?>, Accessor> decorationAccessors = new ConcurrentHashMap<>();
		final BlockState snow = Blocks.SNOW.defaultBlockState();

		private Shared(GlobeTerrain terrain, WorldTraits traits, Map<String, WorldTraitInfo> traitsById, Map<String, ColumnValueInfo> valuesById) {
			this.terrain = terrain;
			this.traits = traits;
			this.foliage = traitsById.get("bigglobe:foliage_at_surface");
			this.palette = traitsById.get("bigglobe:wood_palette");
			this.decorations = traitsById.get("bigglobe:enabled_decorations");
			this.inRiver = traitsById.get("bigglobe:in_river");
			this.exactSurfaceY = traitsById.get("bigglobe:exact_surface_y");
			this.surfaceOverride = valuesById.get("bigglobe:overworld/surface_states_override");
			this.seaLevel = terrain.seaLevel;
			this.seed = terrain.worldInfo.seed() ^ 0x5EED_6E01_0BE7_7EE5L;
		}

		/**
		 * @return null when this dimension has no trees SeedGenGlobe knows how to model,
		 * for example because it has a ceiling or its data pack lacks the traits Big Globe's overworld uses.
		 */
		public static @Nullable Shared create(GlobeTerrain terrain, boolean hasSkyLight, boolean hasCeiling) {
			if (!hasSkyLight || hasCeiling) return null;
			try {
				Map<String, WorldTraitInfo> traitsById = new HashMap<>();
				for (Map.Entry<Holder<WorldTrait>, WorldTraitInfo> entry : WorldTraits.getWorldTraits(terrain.registry).entrySet()) {
					traitsById.put(entry.getValue().id().toString(), entry.getValue());
				}
				Map<String, ColumnValueInfo> valuesById = new HashMap<>();
				for (Map.Entry<Holder<ColumnEntry>, ColumnValueInfo> entry : ScriptedColumn.getColumnValues(terrain.registry).entrySet()) {
					valuesById.put(entry.getValue().id().toString(), entry.getValue());
				}
				if (!traitsById.containsKey("bigglobe:foliage_at_surface") || !traitsById.containsKey("bigglobe:wood_palette")) {
					SeedGenGlobe.LOGGER.info("SeedGenGlobe: no tree canopies in {}: its terrain does not provide foliage and wood palette traits.", terrain.dimension);
					return null;
				}
				return new Shared(terrain, terrain.worldInfo.worldTraits(), traitsById, valuesById);
			}
			catch (Throwable throwable) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: tree canopies disabled in {}: {}", terrain.dimension, throwable.toString());
				return null;
			}
		}
	}

	record PaletteInfo(byte kind, double baseHeight, double foliageHeight, BlockState leaves, BlockState log) {}

	/** Reads a boolean field (or no-arg method) from Big Globe's script-defined EnabledDecorations enum. */
	record Accessor(@Nullable Field field, @Nullable Method method) {

		boolean get(Object object) {
			try {
				if (this.field != null) return this.field.getBoolean(object);
				if (this.method != null) return (Boolean)(this.method.invoke(object));
			}
			catch (ReflectiveOperationException | RuntimeException ignored) {}
			return true;
		}

		static Accessor find(Class<?> type) {
			try {
				Field field = type.getField("grassyFoliage");
				return new Accessor(field, null);
			}
			catch (NoSuchFieldException ignored) {}
			try {
				Method method = type.getMethod("grassyFoliage");
				return new Accessor(null, method);
			}
			catch (NoSuchMethodException ignored) {}
			return new Accessor(null, null);
		}
	}

	private final Shared shared;
	private final SectionSampler sampler;

	private final byte[] kind = new byte[SectionSampler.WIDTH * SectionSampler.WIDTH];
	private final float[] foliage = new float[SectionSampler.WIDTH * SectionSampler.WIDTH];
	private final PaletteInfo[] palettes = new PaletteInfo[SectionSampler.WIDTH * SectionSampler.WIDTH];
	private final int[] ground = new int[SectionSampler.WIDTH * SectionSampler.WIDTH];
	private final boolean[] snowy = new boolean[SectionSampler.WIDTH * SectionSampler.WIDTH];

	public CanopyModel(Shared shared, SectionSampler sampler) {
		this.shared = shared;
		this.sampler = sampler;
	}

	/** Called for every column right after its terrain was computed, while its column values are still cached. */
	void observe(ScriptedColumn column, ColumnProfile profile, int relX, int relZ) {
		int index = relX * SectionSampler.WIDTH + relZ;
		this.kind[index] = NONE;
		if (profile.surfaceRun < 0) return;
		BlockState top = profile.states[profile.surfaceRun];
		// no trees on water, lava or other fluids
		if (!top.getFluidState().isEmpty()) return;
		int surfaceY = profile.surfaceTop;
		boolean snowLayer = top.getBlock() == Blocks.SNOW;
		int groundY = surfaceY;
		if (snowLayer) {
			// the snow layer sits on the real ground
			groundY = profile.bottoms[profile.surfaceRun];
		}
		Shared s = this.shared;
		WorldTraits traits = s.traits;
		Object foliageValue = s.foliage.get(traits, column, surfaceY);
		if (!(foliageValue instanceof Number number)) return;
		float foliage = number.floatValue();
		if (!(foliage > 0.0F)) return;
		if (s.inRiver != null && Boolean.TRUE.equals(s.inRiver.get(traits, column, surfaceY))) return;
		if (s.exactSurfaceY != null && s.exactSurfaceY.get(traits, column, surfaceY) instanceof Number exact) {
			// Big Globe's foliage feature decision tree gives beaches and shallows no trees.
			if (exact.doubleValue() < s.seaLevel + 4.0D) return;
		}
		if (s.decorations != null) {
			Object decorations = s.decorations.get(traits, column, surfaceY);
			if (decorations != null) {
				Accessor accessor = s.decorationAccessors.computeIfAbsent(decorations.getClass(), Accessor::find);
				if (!accessor.get(decorations)) return;
			}
		}
		if (s.surfaceOverride != null && s.surfaceOverride.get(column, surfaceY) != null) return;
		Object paletteValue = s.palette.get(traits, column, surfaceY);
		if (!(paletteValue instanceof WoodPaletteEntry entry)) return;
		PaletteInfo palette = s.palettes.computeIfAbsent(entry.entry, holder -> describe(entry, s.seed));
		if (palette == null || palette.kind == NONE) return;
		this.kind[index] = palette.kind;
		this.foliage[index] = foliage;
		this.palettes[index] = palette;
		this.ground[index] = groundY;
		this.snowy[index] = snowLayer;
	}

	static PaletteInfo describe(WoodPaletteEntry entry, long seed) {
		String id;
		try {
			id = entry.id().toLowerCase(Locale.ROOT);
		}
		catch (RuntimeException exception) {
			id = "";
		}
		BlockState leaves, log;
		try {
			leaves = entry.getSeededState(seed, WoodPaletteType.LEAVES);
			log = entry.getSeededState(seed + 1L, WoodPaletteType.LOG);
		}
		catch (RuntimeException exception) {
			return new PaletteInfo(NONE, 0.0D, 0.0D, null, null);
		}
		String name = id.substring(id.indexOf(':') + 1);
		// heights follow the "height" expressions of Big Globe's natural tree features: base - k * foliage * log2(random)
		return switch (name) {
			case "spruce", "dark_oak", "pale_oak" -> new PaletteInfo(CONE, 12.0D, 12.0D, leaves, log);
			case "birch" -> new PaletteInfo(NARROW, 12.0D, 6.0D, leaves, log);
			case "jungle" -> new PaletteInfo(TALL, 16.0D, 8.0D, leaves, log);
			case "acacia" -> new PaletteInfo(FLAT, 8.0D, 4.0D, leaves, log);
			case "oak", "mangrove" -> new PaletteInfo(SPLIT, 4.0D, 4.0D, leaves, log);
			default -> new PaletteInfo(SPLIT, 6.0D, 4.0D, leaves, log);
		};
	}

	/** -log2(u) has mean 1 / ln 2; Big Globe's tree heights scale it by foliage. */
	static double treeHeight(PaletteInfo palette, double foliage, double u) {
		double logTerm = -Math.log(Math.max(u, 1.0D / 256.0D)) / Math.log(2.0D);
		return Math.min(palette.baseHeight + palette.foliageHeight * foliage * logTerm, 32.0D);
	}

	static double crownRadius(byte kind, double height) {
		return switch (kind) {
			case CONE -> height / 6.0D + 2.0D;
			case NARROW -> Math.min(height * 0.2D + 1.5D, 6.0D);
			case TALL -> Math.min(height * 0.3D + 2.0D, 10.0D);
			case FLAT -> Math.min(height * 0.4D + 2.0D, 9.0D);
			default -> Math.min(height * 0.45D + 1.5D, 10.0D);
		};
	}

	/** Canopy top (exclusive) at horizontal distance d from the trunk, relative to the ground. */
	static double crownTop(byte kind, double height, double radius, double d) {
		double f = Math.min(d / radius, 1.0D);
		return switch (kind) {
			case CONE -> height + 1.0D - 0.75D * height * f;
			case FLAT -> height + 1.0D - f;
			case NARROW -> height + 1.0D - f * f * 0.3D * height;
			case TALL -> height + 2.0D - f * f * 0.4D * height;
			default -> height + 2.0D - f * f * 0.5D * height;
		};
	}

	/** Canopy bottom (inclusive) relative to the ground. */
	static double crownBottom(byte kind, double height, double radius, double d) {
		double f = Math.min(d / radius, 1.0D);
		return switch (kind) {
			case CONE -> 0.25D * height;
			case FLAT -> 0.75D * height;
			case NARROW -> 0.35D * height + f * 0.15D * height;
			case TALL -> 0.6D * height + f * 0.15D * height;
			default -> 0.5D * height + f * 0.2D * height;
		};
	}

	void apply(int originX, int originZ, int detail, int levelMinY) {
		if (detail <= EXPLICIT_MAX_DETAIL) {
			this.applyExplicit(originX, originZ, detail);
		}
		else {
			this.applyStatistical(originX, originZ, detail);
		}
	}

	private void applyExplicit(int originX, int originZ, int detail) {
		int width = SectionSampler.WIDTH;
		int step = 1 << detail;
		int half = this.sampler.halfCell(detail);
		int span = width << detail;
		int margin = 12 + step;
		int minGX = Math.floorDiv(originX - margin, GRID), maxGX = Math.floorDiv(originX + span + margin, GRID);
		int minGZ = Math.floorDiv(originZ - margin, GRID), maxGZ = Math.floorDiv(originZ + span + margin, GRID);
		double inflate = step > 1 ? step * 0.35D : 0.0D;
		for (int gz = minGZ; gz <= maxGZ; gz++) {
			for (int gx = minGX; gx <= maxGX; gx++) {
				long hash = mix(this.shared.seed, gx, gz);
				if (unit(hash >>> 40) >= SUCCESS) continue;
				int treeX = gx * GRID + (int)(hash & 7L);
				int treeZ = gz * GRID + (int)((hash >>> 3) & 7L);
				int siteX = clamp(Math.floorDiv(treeX - originX, step), 0, width - 1);
				int siteZ = clamp(Math.floorDiv(treeZ - originZ, step), 0, width - 1);
				int site = siteX * width + siteZ;
				byte kind = this.kind[site];
				if (kind == NONE) continue;
				PaletteInfo palette = this.palettes[site];
				double height = treeHeight(palette, this.foliage[site], unit(hash >>> 16) + 1.0E-9D);
				double radius = crownRadius(kind, height);
				double reach = radius + inflate;
				int groundY = this.ground[site];
				double centerX = treeX + 0.5D, centerZ = treeZ + 0.5D;
				int minRX = clamp((int)(Math.floor((centerX - reach - originX - half) / step)), 0, width - 1);
				int maxRX = clamp((int)(Math.ceil((centerX + reach - originX - half) / step)), 0, width - 1);
				int minRZ = clamp((int)(Math.floor((centerZ - reach - originZ - half) / step)), 0, width - 1);
				int maxRZ = clamp((int)(Math.ceil((centerZ + reach - originZ - half) / step)), 0, width - 1);
				for (int rx = minRX; rx <= maxRX; rx++) {
					double px = originX + (rx << detail) + half + 0.5D;
					for (int rz = minRZ; rz <= maxRZ; rz++) {
						double pz = originZ + (rz << detail) + half + 0.5D;
						double d = Math.sqrt((px - centerX) * (px - centerX) + (pz - centerZ) * (pz - centerZ));
						if (d >= reach) continue;
						double effective = Math.max(d - inflate, 0.0D);
						int top = groundY + (int)(Math.round(crownTop(kind, height, radius, effective)));
						int bottom = groundY + (int)(Math.round(crownBottom(kind, height, radius, effective)));
						// trunk first: leaves are only ever laid above a column's current surface
						if (effective < 0.75D && step <= 2) {
							this.layTrunk(rx, rz, palette, groundY, bottom);
						}
						this.layLeaves(rx, rz, palette, bottom, top, this.snowy[site]);
					}
				}
			}
		}
	}

	private void applyStatistical(int originX, int originZ, int detail) {
		int width = SectionSampler.WIDTH;
		int half = this.sampler.halfCell(detail);
		for (int rx = 0; rx < width; rx++) {
			for (int rz = 0; rz < width; rz++) {
				int index = rx * width + rz;
				byte kind = this.kind[index];
				if (kind == NONE) continue;
				PaletteInfo palette = this.palettes[index];
				int x = originX + (rx << detail) + half;
				int z = originZ + (rz << detail) + half;
				long hash = mix(this.shared.seed ^ detail, x, z);
				double meanHeight = palette.baseHeight + palette.foliageHeight * this.foliage[index] * 1.4426950408889634D;
				double radius = crownRadius(kind, meanHeight);
				// fraction of the ground a forest of 0.8 trees per 64 square blocks covers with crowns of this radius
				double coverage = 1.0D - Math.exp(-SUCCESS / (GRID * GRID) * Math.PI * radius * radius);
				if (unit(hash) >= coverage) continue;
				double height = treeHeight(palette, this.foliage[index], unit(hash >>> 20) + 1.0E-9D);
				double d = unit(hash >>> 40) * radius * 0.6D;
				int groundY = this.ground[index];
				int top = groundY + (int)(Math.round(crownTop(kind, height, radius, d)));
				int bottom = groundY + (int)(Math.round(crownBottom(kind, height, radius, d)));
				this.layLeaves(rx, rz, palette, bottom, top, this.snowy[index]);
			}
		}
	}

	private void layLeaves(int rx, int rz, PaletteInfo palette, int bottom, int top, boolean snowy) {
		ColumnProfile profile = this.sampler.profile(rx, rz);
		// no trees grow where a structure stands
		if (profile.count == 0 || this.sampler.occupied(rx, rz)) return;
		// never bury terrain: everything above a column's surface is air, so leaves only go there
		bottom = Math.max(bottom, profile.surfaceTop);
		if (top <= bottom) return;
		int sky = skyAt(profile, bottom);
		profile.overlay(palette.leaves, bottom, top, sky, 0);
		if (snowy) {
			profile.overlay(this.shared.snow, top, top + 1, sky, 0);
		}
	}

	private void layTrunk(int rx, int rz, PaletteInfo palette, int groundY, int bottom) {
		ColumnProfile profile = this.sampler.profile(rx, rz);
		if (profile.count == 0 || this.sampler.occupied(rx, rz)) return;
		int from = Math.max(groundY, profile.surfaceTop);
		if (bottom <= from) return;
		profile.overlay(palette.log, from, bottom, skyAt(profile, from), 0);
	}

	/** Sky light of the air a canopy replaces, so trees in the shade of a floating island stay in its shade. */
	static int skyAt(ColumnProfile profile, int y) {
		int run = profile.runAt(y);
		return run >= 0 ? profile.skyLight[run] : 15;
	}

	static int clamp(int value, int min, int max) {
		return value < min ? min : (value > max ? max : value);
	}

	static double unit(long bits) {
		return (bits & 0xFFFFFL) / (double)(1L << 20);
	}

	static long mix(long seed, int x, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		return h ^ (h >>> 31);
	}
}
