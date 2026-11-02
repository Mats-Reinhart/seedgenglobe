package dev.seedgenglobe.terrain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import builderb0y.bigglobe.columns.ScriptedColumn;
import builderb0y.bigglobe.columns.ScriptedColumn.ColumnValueInfo;
import builderb0y.bigglobe.columns.entries.ColumnEntry;
import builderb0y.bigglobe.columns.traits.WorldTrait;
import builderb0y.bigglobe.columns.traits.WorldTraits;
import builderb0y.bigglobe.columns.traits.WorldTraits.WorldTraitInfo;
import builderb0y.bigglobe.features.FlowerControllerFeature;
import builderb0y.bigglobe.features.FlowerFeature;
import builderb0y.bigglobe.features.SingleBlockFeature;
import builderb0y.bigglobe.math.BigGlobeMath;
import builderb0y.bigglobe.math.Interpolator;
import builderb0y.bigglobe.noise.Permuter;
import builderb0y.bigglobe.scripting.wrappers.entries.WoodPaletteEntry;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * Big Globe's ground cover for LOD columns: grass, ferns, flowers, leaf litter, petals, sticks, rocks and bushes.
 * <p>
 * Real chunks are covered in these small blocks. Distant Horizons does not draw most of them, but it paints the
 * block underneath with their colour ("tint with avoided blocks", on by default), and when it shrinks real chunks
 * to coarser detail levels it keeps the most common non-air block of every 2 x 2 group, which is almost always a
 * plant. So distant LODs of explored terrain are coloured by plants and flowers, not by grass blocks, and without
 * this model explored areas stand out as differently coloured squares in generated terrain.
 * <p>
 * The model reproduces Big Globe's overworld decorators: the flower controller exactly (same seeds, same patch
 * shapes, same entry restrictions) and the per-block decorators statistically, with their chances computed from
 * the same world traits Big Globe's scripts read. For a coarse LOD cell it decides up to 16 blocks inside the cell
 * and combines them the way Distant Horizons combines real chunks (most common non-air block of each 2 x 2 group),
 * so a cell shows what the same area would show had it been generated for real. Every block's decision depends
 * only on its position, so all detail levels agree with each other.
 * <p>
 * Not thread safe: one instance per {@link SectionSampler}.
 */
public final class GroundCover {

	// ground kinds
	static final byte OTHER = 0, SOIL = 1, SAND = 2, TERRACOTTA = 3, MUD = 4, MYCELIUM = 5, BLACKSTONE = 6, NOT_GROUND = 7;

	/** One kind of decoration. Index 0 of the table means "nothing". */
	public static final class Decoration {

		final int index;
		final BlockState lower;
		final @Nullable BlockState upper;
		/** Replaces the ground block (farmland under crops), only if the ground is in {@link #underReplaces}. */
		final @Nullable BlockState under;
		final @Nullable Set<BlockState> underReplaces;
		/** Grass and similar plants that later decorators may replace. */
		final boolean replaceable;
		/** Needs soil (grass, dirt, podzol, ...) to survive; otherwise any solid ground will do. */
		final boolean needsSoil;
		/** A bush: leaves instead of a plant, one or two blocks high. */
		final boolean bush;

		Decoration(int index, BlockState lower, @Nullable BlockState upper, @Nullable BlockState under, @Nullable Set<BlockState> underReplaces, boolean replaceable, boolean needsSoil, boolean bush) {
			this.index = index;
			this.lower = lower;
			this.upper = upper;
			this.under = under;
			this.underReplaces = underReplaces;
			this.replaceable = replaceable;
			this.needsSoil = needsSoil;
			this.bush = bush;
		}
	}

	/** Reads boolean fields of Big Globe's script-defined EnabledDecorations enum. */
	static final class DecorationFlags {

		final @Nullable Field grassy, hot, deadBushes, mushrooms, charred;

		DecorationFlags(Class<?> type) {
			this.grassy = field(type, "grassyFoliage");
			this.hot = field(type, "hotFoliage");
			this.deadBushes = field(type, "deadBushes");
			this.mushrooms = field(type, "mushrooms");
			this.charred = field(type, "charredGrass");
		}

		static @Nullable Field field(Class<?> type, String name) {
			try {
				Field field = type.getField(name);
				return field.getType() == boolean.class ? field : null;
			}
			catch (NoSuchFieldException | SecurityException exception) {
				return null;
			}
		}

		static boolean get(@Nullable Field field, Object object, boolean fallback) {
			if (field == null) return fallback;
			try {
				return field.getBoolean(object);
			}
			catch (ReflectiveOperationException | RuntimeException exception) {
				return fallback;
			}
		}
	}

	/** Decoration indices of the per-block decorators (0 when the block does not exist in this game version). */
	static final class Codes {

		int shortGrass, fern, tallGrass, largeFern, bigGlobeShortGrass, deadBush, shortDryGrass, tallDryGrass;
		int pinkPetals, leafLitter, stick, rock, mushroomSpores, charredGrass;
		/** Whether a decoration may be replaced by later decorators (grass and similar plants). */
		java.util.function.IntPredicate replaceable = index -> index == 0;
	}

	/** Everything shared by the samplers of one terrain. */
	public static final class Shared {

		final GlobeTerrain terrain;
		final WorldTraits traits;
		final WorldTraitInfo foliageSurface, foliageAt, temperature, humidity, magicalness, snowChance, decorations;
		final @Nullable WorldTraitInfo inRiver, exactSurfaceY, hilliness, palette;
		final @Nullable ColumnValueInfo surfaceOverride;
		final int seaLevel;
		final long seed;
		/** Seed Big Globe's flower controller uses: the world seed on a server, the column seed on a client. */
		final long flowerSeed;
		final @Nullable FlowerControllerFeature.Config flowerController;
		final FlowerFeature.Config @Nullable [] flowerGroups;
		final ConcurrentHashMap<Class<?>, DecorationFlags> decorationFlags = new ConcurrentHashMap<>();
		final ConcurrentHashMap<BlockState, Byte> groundKinds = new ConcurrentHashMap<>();
		final ConcurrentHashMap<Object, CanopyModel.PaletteInfo> palettes = new ConcurrentHashMap<>();
		final ConcurrentHashMap<Object, Integer> decorationIndices = new ConcurrentHashMap<>();
		final List<Decoration> table = new CopyOnWriteArrayList<>();
		final Set<BlockState> soil = ConcurrentHashMap.newKeySet();
		final Set<BlockState> sand = ConcurrentHashMap.newKeySet();
		final Set<BlockState> terracotta = ConcurrentHashMap.newKeySet();
		final BlockState snowLayer = Blocks.SNOW.defaultBlockState();
		final Codes codes = new Codes();
		final int wildflowers, redWildflowers, bluebonnets, violets;

		private Shared(GlobeTerrain terrain, Map<String, WorldTraitInfo> traitsById, Map<String, ColumnValueInfo> valuesById, long flowerSeed, @Nullable FlowerControllerFeature.Config flowerController) {
			this.terrain = terrain;
			this.traits = terrain.worldInfo.worldTraits();
			this.foliageSurface = traitsById.get("bigglobe:foliage_at_surface");
			this.foliageAt = traitsById.getOrDefault("bigglobe:foliage_at", this.foliageSurface);
			this.temperature = traitsById.get("bigglobe:temperature_at");
			this.humidity = traitsById.get("bigglobe:humidity_at");
			this.magicalness = traitsById.get("bigglobe:magicalness");
			this.snowChance = traitsById.get("bigglobe:snow_chance");
			this.decorations = traitsById.get("bigglobe:enabled_decorations");
			this.inRiver = traitsById.get("bigglobe:in_river");
			this.exactSurfaceY = traitsById.get("bigglobe:exact_surface_y");
			this.hilliness = traitsById.get("bigglobe:hilliness");
			this.palette = traitsById.get("bigglobe:wood_palette");
			this.surfaceOverride = valuesById.get("bigglobe:overworld/surface_states_override");
			this.seaLevel = terrain.seaLevel;
			this.seed = terrain.worldInfo.seed() ^ 0x6C0D_C0FE_E5EE_D5A1L;
			this.flowerSeed = flowerSeed;
			this.flowerController = flowerController;
			FlowerFeature.Config[] groups = null;
			if (flowerController != null) {
				try {
					groups = flowerController.getFlattenedFlowers();
				}
				catch (RuntimeException exception) {
					SeedGenGlobe.LOGGER.warn("SeedGenGlobe: could not read Big Globe's flower groups; LODs get no flower patches.", exception);
				}
			}
			this.flowerGroups = groups;

			this.table.add(null); // index 0: nothing
			for (String id : new String[] { "grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt", "moss_block", "pale_moss_block", "farmland", "bigglobe:overgrown_podzol" }) {
				addAll(this.soil, id);
			}
			for (String id : new String[] { "sand", "red_sand", "suspicious_sand", "bigglobe:overgrown_sand" }) {
				addAll(this.sand, id);
			}
			for (String color : new String[] { "", "white_", "orange_", "magenta_", "light_blue_", "yellow_", "lime_", "pink_", "gray_", "light_gray_", "cyan_", "purple_", "blue_", "brown_", "green_", "red_", "black_" }) {
				addAll(this.terracotta, color + "terracotta");
			}

			Codes k = this.codes;
			k.shortGrass = this.plant("short_grass", null, true, true);
			k.fern = this.plant("fern", null, true, true);
			k.tallGrass = this.plant("tall_grass", "lower", true, true);
			k.largeFern = this.plant("large_fern", "lower", true, true);
			k.bigGlobeShortGrass = this.plant("bigglobe:short_grass", null, true, true);
			k.deadBush = this.plant("dead_bush", null, true, false);
			k.shortDryGrass = this.plant("short_dry_grass", null, true, false);
			k.tallDryGrass = this.plant("tall_dry_grass", null, true, false);
			k.pinkPetals = this.plant("pink_petals", null, false, true);
			k.leafLitter = this.plant("leaf_litter", null, true, false);
			k.stick = this.plant("bigglobe:stick", null, false, false);
			k.rock = this.plant("bigglobe:rock", null, false, false);
			k.mushroomSpores = this.plant("bigglobe:mushroom_spores", null, true, false);
			k.charredGrass = this.plant("bigglobe:charred_grass", null, true, false);
			k.replaceable = index -> index == 0 || this.table.get(index).replaceable;
			this.wildflowers = this.plant("wildflowers", null, false, true);
			this.redWildflowers = this.plant("bigglobe:red_wildflowers", null, false, true);
			this.bluebonnets = this.plant("bigglobe:bluebonnets", null, false, true);
			this.violets = this.plant("bigglobe:violets", null, false, true);
		}

		/**
		 * @param flowerSeed the world seed when known (server side), otherwise any stable seed
		 * @param flowerController Big Globe's overworld flower controller (server side only), or null
		 * @return null when this terrain is not a Big Globe overworld with the traits its decorators read
		 */
		public static @Nullable Shared create(GlobeTerrain terrain, boolean hasSkyLight, boolean hasCeiling, long flowerSeed, @Nullable FlowerControllerFeature.Config flowerController) {
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
				for (String required : new String[] { "bigglobe:foliage_at_surface", "bigglobe:temperature_at", "bigglobe:humidity_at", "bigglobe:magicalness", "bigglobe:snow_chance", "bigglobe:enabled_decorations" }) {
					if (!traitsById.containsKey(required)) {
						SeedGenGlobe.LOGGER.info("SeedGenGlobe: no ground cover in {}: its terrain has no {} trait.", terrain.dimension, required);
						return null;
					}
				}
				return new Shared(terrain, traitsById, valuesById, flowerSeed, flowerController);
			}
			catch (Throwable throwable) {
				if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: ground cover disabled in {}: {}", terrain.dimension, throwable.toString());
				return null;
			}
		}

		public boolean hasFlowers() {
			return this.flowerGroups != null && this.flowerGroups.length > 0 && this.flowerController != null;
		}

		private static void addAll(Set<BlockState> set, String id) {
			Block block = block(id);
			if (block == null) return;
			for (Object state : (Iterable<?>)(Object)(block.getStateDefinition().getPossibleStates())) {
				if (state instanceof BlockState blockState) set.add(blockState);
			}
		}

		static @Nullable Block block(String id) {
			Identifier identifier = Identifier.tryParse(id.indexOf(':') >= 0 ? id : "minecraft:" + id);
			if (identifier == null) return null;
			Object value = BuiltInRegistries.BLOCK.getValue(identifier);
			Block block = value instanceof Block found ? found : null;
			// the block registry answers air for unknown ids
			return block == null || (block == Blocks.AIR && !identifier.getPath().equals("air")) ? null : block;
		}

		/** @return the decoration index, or 0 if this block does not exist in this game version */
		private int plant(String id, @Nullable String half, boolean replaceable, boolean needsSoil) {
			Block block = block(id);
			if (block == null) return 0;
			BlockState lower = block.defaultBlockState();
			BlockState upper = null;
			if (half != null) {
				lower = BlockStates.with(lower, "half", "lower");
				upper = BlockStates.with(lower, "half", "upper");
				if (upper == lower) upper = null;
			}
			lower = BlockStates.with(lower, "waterlogged", "false");
			return this.register(lower, lower, upper, null, null, replaceable, needsSoil, false);
		}

		int register(Object key, BlockState lower, @Nullable BlockState upper, @Nullable BlockState under, @Nullable Set<BlockState> underReplaces, boolean replaceable, boolean needsSoil, boolean bush) {
			Integer existing = this.decorationIndices.get(key);
			if (existing != null) return existing;
			synchronized (this.table) {
				existing = this.decorationIndices.get(key);
				if (existing != null) return existing;
				int index = this.table.size();
				this.table.add(new Decoration(index, lower, upper, under, underReplaces, replaceable, needsSoil, bush));
				this.decorationIndices.put(key, index);
				return index;
			}
		}

		/** A flower controller entry as a decoration (double flowers get their upper half, crops their farmland). */
		int flowerDecoration(FlowerFeature.Entry entry, long placeSeed) {
			SingleBlockFeature.Config config = entry.state();
			List<BlockState> places = config.place();
			BlockState state = places.size() == 1 ? places.get(0) : places.get(Permuter.nextBoundedInt(placeSeed, places.size()));
			Integer known = this.decorationIndices.get(state);
			if (known != null) return known;
			BlockState lower = BlockStates.with(state, "half", "lower");
			BlockState upper = BlockStates.with(lower, "half", "upper");
			if (upper == lower) upper = null;
			BlockState under = null;
			Set<BlockState> underReplaces = null;
			SingleBlockFeature.Config underConfig = entry.under();
			if (underConfig != null && !underConfig.place().isEmpty()) {
				under = underConfig.place().get(0);
				underReplaces = underConfig.replace();
			}
			// crops need their farmland; mushrooms grow on any solid block; everything else needs soil
			String name = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
			boolean needsSoil = under == null && !name.endsWith("mushroom");
			return this.register(state, lower, upper, under, underReplaces, false, needsSoil, false);
		}

		int bushDecoration(BlockState leaves) {
			return this.register(new BushKey(leaves), leaves, null, null, null, false, false, true);
		}

		record BushKey(BlockState leaves) {}

		byte groundKind(BlockState state) {
			Byte known = this.groundKinds.get(state);
			if (known != null) return known;
			byte kind;
			if (this.soil.contains(state)) kind = SOIL;
			else if (this.sand.contains(state)) kind = SAND;
			else if (this.terracotta.contains(state)) kind = TERRACOTTA;
			else if (state.getBlock() == Blocks.MUD) kind = MUD;
			else if (state.getBlock() == Blocks.MYCELIUM) kind = MYCELIUM;
			else if (state.getBlock() == Blocks.BLACKSTONE) kind = BLACKSTONE;
			else if (state.isAir() || !state.getFluidState().isEmpty() || state.getBlock() == Blocks.SNOW || state.canBeReplaced()) kind = NOT_GROUND;
			else kind = OTHER;
			this.groundKinds.put(state, kind);
			return kind;
		}

		@Nullable CanopyModel.PaletteInfo palette(ScriptedColumn column, int y) {
			if (this.palette == null) return null;
			Object value = this.palette.get(this.traits, column, y);
			if (!(value instanceof WoodPaletteEntry entry)) return null;
			CanopyModel.PaletteInfo info = this.palettes.computeIfAbsent(entry.entry, holder -> CanopyModel.describe(entry, this.seed));
			return info.leaves() != null ? info : null;
		}

		DecorationFlags flags(Object decorations) {
			return this.decorationFlags.computeIfAbsent(decorations.getClass(), DecorationFlags::new);
		}
	}

	// ------------------------------------------------------------------------------------------------------------

	/** Chances of one LOD column, computed once from its traits and shared by all blocks decided for it. */
	static final class Context {

		byte ground;
		boolean grassy, hot, deadBushesAllowed, mushrooms, charred;
		double adjustedFoliage, deadBushChance, fernChance;
		double petalWeight, litterWeight, wildWeight, cherryTotal, cold;
		int wildKind;
		double stickChance, rockChance;
		double bushChance;
		int bushDecoration;
		/** Flower patches near the column, for coarse cells: decoration index and chance per block. */
		int candidates;
		final int[] candidateDecoration = new int[64];
		final double[] candidateChance = new double[64];
		double flowerChance;
		/** y level Big Globe's flower controller places at, or Integer.MIN_VALUE when it places nothing here. */
		int flowerY;
	}

	/** Draws a sequence of uniform numbers from a block position. */
	static final class Draws {

		long state;

		void start(long seed, int x, int z) {
			this.state = CanopyModel.mix(seed, x, z);
		}

		double next() {
			// Stafford's mix 13 of a Weyl sequence, like Big Globe's Permuter (kept local so tests need no Big Globe runtime)
			long z = this.state += 0x9E3779B97F4A7C15L;
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			return ((z ^ (z >>> 31)) >>> 11) * 0x1.0p-53D;
		}
	}

	private final Shared shared;
	private final SectionSampler sampler;
	private final Context context = new Context();
	private final Draws draws = new Draws();
	private final int[] decision = new int[SectionSampler.WIDTH * SectionSampler.WIDTH];
	private final int[] groundTop = new int[SectionSampler.WIDTH * SectionSampler.WIDTH];
	private final int[] samples = new int[16];
	private final int[] merged = new int[4];
	/** Per flower group: restricted weight of every entry for the current column. */
	private double[][] weights = new double[0][];
	private final double[] groupTotals;
	private final boolean[] groupAllowed;
	private final double[] groupNoise;
	private final long[] groupSeeds;

	public GroundCover(Shared shared, SectionSampler sampler) {
		this.shared = shared;
		this.sampler = sampler;
		int groups = shared.hasFlowers() ? shared.flowerGroups.length : 0;
		this.weights = new double[groups][];
		for (int group = 0; group < groups; group++) {
			this.weights[group] = new double[shared.flowerGroups[group].entries.elements.size()];
		}
		this.groupTotals = new double[groups];
		this.groupAllowed = new boolean[groups];
		this.groupNoise = new double[groups];
		this.groupSeeds = new long[groups];
	}

	/** Starts a section: nothing is decided yet. */
	void begin() {
		java.util.Arrays.fill(this.decision, 0);
	}

	/** Drops the decision for a column whose decorators failed. */
	void forget(int relX, int relZ) {
		this.decision[relX * SectionSampler.WIDTH + relZ] = 0;
	}

	/**
	 * Decides the ground cover of one column. Called right after the column's terrain was computed, while its column
	 * values are still cached, and before tree canopies are laid.
	 *
	 * @param cellX minimum block x of the LOD cell
	 * @param cellZ minimum block z of the LOD cell
	 */
	void observe(ScriptedColumn column, ColumnProfile profile, int relX, int relZ, int cellX, int cellZ, int detail) {
		int index = relX * SectionSampler.WIDTH + relZ;
		this.decision[index] = 0;
		if (profile.surfaceRun < 0) return;
		BlockState top = profile.states[profile.surfaceRun];
		byte ground = this.shared.groundKind(top);
		if (ground == NOT_GROUND) return;
		int surfaceY = profile.surfaceTop;
		this.groundTop[index] = surfaceY;
		this.flowerPatchesCollected = false;
		if (!this.prepare(column, ground, surfaceY)) return;
		int result;
		if (detail == 0) {
			result = this.decide(column, cellX, cellZ, true);
		}
		else {
			// decide 4 or 16 blocks of the cell and combine them like Distant Horizons combines real chunks
			int perSide = detail == 1 ? 2 : 4;
			int stride = detail <= 2 ? 1 : 1 << (detail - 2);
			boolean exactFlowers = detail == 1;
			int count = 0;
			for (int i = 0; i < perSide; i++) {
				for (int j = 0; j < perSide; j++) {
					this.samples[count++] = this.decide(column, cellX + i * stride, cellZ + j * stride, exactFlowers);
				}
			}
			result = perSide == 2 ? mostCommon(this.samples[0], this.samples[1], this.samples[2], this.samples[3]) : this.combine16();
		}
		this.decision[index] = result;
	}

	/** Two rounds of Distant Horizons' 2 x 2 merge over 4 x 4 samples stored x-major (i * 4 + j). */
	private int combine16() {
		int[] s = this.samples;
		for (int quadX = 0; quadX < 2; quadX++) {
			for (int quadZ = 0; quadZ < 2; quadZ++) {
				int base = quadX * 8 + quadZ * 2;
				this.merged[quadX * 2 + quadZ] = mostCommon(s[base], s[base + 1], s[base + 4], s[base + 5]);
			}
		}
		return mostCommon(this.merged[0], this.merged[1], this.merged[2], this.merged[3]);
	}

	/**
	 * Distant Horizons' choice for one slice of a 2 x 2 group: the most common non-air value, ties going to the
	 * first; order (x, z), (x, z + 1), (x + 1, z), (x + 1, z + 1). Reimplemented from
	 * {@code FullDataSourceV2.determineMostCommonValueInColumnSlice}, quirks included.
	 */
	static int mostCommon(int v0, int v1, int v2, int v3) {
		int c0 = 0, c1 = 0, c2 = 0, c3 = 0;
		for (int i = 0; i < 4; i++) {
			int value = i == 0 ? v0 : (i == 1 ? v1 : (i == 2 ? v2 : v3));
			if (value == 0) continue;
			if (value == v0) c0++;
			else if (value == v1) c1++;
			else if (value == v2) c2++;
			else c3++;
		}
		int max = Math.max(c0, Math.max(c1, Math.max(c2, c3)));
		if (max == c0) return v0;
		if (max == c1) return v1;
		return max == c2 ? v2 : v3;
	}

	/** Computes the chances of the column's decorations. @return false when nothing can grow here. */
	private boolean prepare(ScriptedColumn column, byte ground, int surfaceY) {
		Shared s = this.shared;
		Context c = this.context;
		WorldTraits traits = s.traits;
		Object decorations = s.decorations.get(traits, column, surfaceY);
		if (decorations == null) return false;
		DecorationFlags flags = s.flags(decorations);
		c.ground = ground;
		c.grassy = DecorationFlags.get(flags.grassy, decorations, false);
		c.hot = DecorationFlags.get(flags.hot, decorations, false);
		c.deadBushesAllowed = DecorationFlags.get(flags.deadBushes, decorations, false);
		c.mushrooms = DecorationFlags.get(flags.mushrooms, decorations, false);
		c.charred = DecorationFlags.get(flags.charred, decorations, false);
		if (!c.grassy && !c.hot && !c.deadBushesAllowed && !c.mushrooms && !c.charred) return false;

		double foliageSurface = number(s.foliageSurface.get(traits, column, surfaceY));
		double foliageAt = number(s.foliageAt.get(traits, column, surfaceY));
		double temperature = number(s.temperature.get(traits, column, surfaceY));
		double humidity = number(s.humidity.get(traits, column, surfaceY));
		double magicalness = number(s.magicalness.get(traits, column, surfaceY));
		double snowChance = number(s.snowChance.get(traits, column, surfaceY));
		boolean river = s.inRiver != null && Boolean.TRUE.equals(s.inRiver.get(traits, column, surfaceY));
		boolean underwater = s.exactSurfaceY != null && number(s.exactSurfaceY.get(traits, column, surfaceY)) < s.seaLevel;

		// surface_small_foliage
		double adjusted = foliageSurface / Math.sqrt(foliageSurface * foliageSurface + 1.0D);
		c.adjustedFoliage = adjusted * 0.5D + 0.5D;
		double fromTemperate = temperature * temperature + foliageAt * foliageAt;
		c.deadBushChance = fromTemperate / (fromTemperate * 64.0D + 4.0D);
		c.fernChance = humidity * 0.125D + 0.125D;

		// cherry_blossoms: pink petals, leaf litter, wildflowers
		double hotChance = unmixSmooth(0.75D, 0.5D, temperature);
		double temperateChance = unmixSmooth(0.125D, -0.125D, temperature);
		c.cold = clamp01(1.0D - snowChance);
		double forestChance = curve(foliageSurface, 0.25D);
		double plainsChance = Math.max(-foliageSurface * (foliageSurface + 1.0D), 0.0D);
		double petalChance = curve(magicalness - 0.5D, 1.0D);
		double leafChance = curve(1.0D - magicalness, 1.0D);
		c.petalWeight = s.codes.pinkPetals != 0 ? forestChance * petalChance * temperateChance : 0.0D;
		c.litterWeight = s.codes.leafLitter != 0 ? forestChance * leafChance * hotChance : 0.0D;
		c.wildWeight = plainsChance * hotChance;
		c.wildKind = c.wildWeight > 0.0D ? this.wildflowerKind(column.x(), column.z()) : 0;
		if (c.wildKind == 0) c.wildWeight = 0.0D;
		c.cherryTotal = c.grassy ? c.petalWeight + c.litterWeight + c.wildWeight : 0.0D;

		// surface_materials: sticks (forests) and rocks (hills), per block
		c.stickChance = c.grassy && s.codes.stick != 0 ? Math.max(foliageSurface, 0.0D) * 16.0D / 256.0D : 0.0D;
		c.rockChance = s.codes.rock != 0 && s.hilliness != null ? Math.max(number(s.hilliness.get(traits, column, surfaceY)), 0.0D) * 16.0D / 256.0D : 0.0D;

		// bushes: round((foliage + 0.25)^2 * 8) per chunk, leaf blobs of radius 1 to 3 (13.6 blocks on average)
		c.bushChance = 0.0D;
		c.bushDecoration = 0;
		if (c.grassy && !river && !underwater && foliageSurface > -0.25D && (c.ground == SOIL || c.ground == SAND)) {
			if (s.surfaceOverride == null || s.surfaceOverride.get(column, surfaceY) == null) {
				CanopyModel.PaletteInfo palette = s.palette(column, surfaceY);
				if (palette != null) {
					double perChunk = (foliageSurface + 0.25D) * (foliageSurface + 0.25D) * 8.0D;
					c.bushChance = 1.0D - Math.exp(-perChunk * 13.6D / 256.0D);
					c.bushDecoration = s.bushDecoration(palette.leaves());
				}
			}
		}

		// flowers: Big Globe's flower controller
		c.flowerY = Integer.MIN_VALUE;
		c.candidates = 0;
		c.flowerChance = 0.0D;
		if (s.hasFlowers()) this.prepareFlowers(column);
		return true;
	}

	private void prepareFlowers(ScriptedColumn column) {
		Shared s = this.shared;
		Context c = this.context;
		FlowerControllerFeature.Config controller = s.flowerController;
		if (controller.where != null && !controller.where.get(column)) return;
		int y = controller.y_level.get(column);
		if (y < this.shared.terrain.minY || y >= this.shared.terrain.maxY) return;
		c.flowerY = y;
		FlowerFeature.Config[] groups = s.flowerGroups;
		boolean any = false;
		for (int group = 0; group < groups.length; group++) {
			FlowerFeature.Config link = groups[group];
			boolean allowed = link.canPlaceAt(column, y);
			this.groupAllowed[group] = allowed;
			if (!allowed) continue;
			List<FlowerFeature.Entry> elements = link.entries.elements;
			double[] weights = this.weights[group];
			double total = 0.0D;
			for (int entry = 0; entry < weights.length; entry++) {
				double weight = elements.get(entry).getRestrictedWeight(column, y);
				weights[entry] = weight;
				if (weight > 0.0D) total += weight;
			}
			this.groupTotals[group] = total;
			if (total > 0.0D) {
				long groupSeed = link.seed.xor(s.flowerSeed);
				this.groupSeeds[group] = groupSeed;
				this.groupNoise[group] = link.noise.getValue(groupSeed, column.x(), column.z());
				any = true;
			}
		}
		if (!any) c.flowerY = Integer.MIN_VALUE;
	}

	/** {@code IRandomList.getRandomIndex(long)} over this column's cached weights. */
	static int randomIndex(double[] weights, long seed) {
		int choice = weights.length - 1;
		double total = 0.0D;
		for (int index = 0; index < weights.length; index++) {
			double weight = weights[index];
			if (weight > 0.0D && Permuter.nextPositiveDouble(seed += Permuter.PHI64) * (total += weight) < weight) {
				choice = index;
			}
		}
		return total > 0.0D ? choice : -1;
	}

	/**
	 * Big Globe's {@code FlowerControllerFeature.getQueuedPlacement} for the block (x, z), using the traits of the
	 * column being generated (for coarse cells that column is up to a cell away; flower patches are much larger).
	 *
	 * @param collect true to collect (decoration, chance) of every patch reaching this block instead of drawing one,
	 * for coarse cells
	 * @return the decoration index of the placed flower, or 0
	 */
	private int flowerAt(ScriptedColumn column, int x, int z, boolean collect) {
		Shared s = this.shared;
		Context c = this.context;
		int y = c.flowerY;
		long overlapSeed = Permuter.permute(s.flowerSeed ^ 0x3C8F9545BAE6971FL, x, z);
		int overlapChance = 0;
		FlowerFeature.Entry chosen = null;
		FlowerFeature.Config[] groups = s.flowerGroups;
		for (int group = 0; group < groups.length; group++) {
			if (!this.groupAllowed[group] || !(this.groupTotals[group] > 0.0D)) continue;
			FlowerFeature.Config link = groups[group];
			List<FlowerFeature.Entry> elements = link.entries.elements;
			double[] weights = this.weights[group];
			long groupSeed = this.groupSeeds[group];
			int scale = link.distance;
			int variation = link.variation;
			int inGridX = BigGlobeMath.modulus_BP(x, scale);
			int inGridZ = BigGlobeMath.modulus_BP(z, scale);
			int gridStartX = x - inGridX;
			int gridStartZ = z - inGridZ;
			double noise = collect ? this.groupNoise[group] : link.noise.getValue(groupSeed, x, z);
			double maxNoise = link.noise.maxValue();
			for (int offsetX = -scale; offsetX <= scale; offsetX += scale) {
				for (int offsetZ = -scale; offsetZ <= scale; offsetZ += scale) {
					long otherGridSeed = Permuter.permute(groupSeed ^ 0xA2BBF085229FA361L, gridStartX + offsetX, gridStartZ + offsetZ);
					if (!Permuter.nextChancedBoolean(otherGridSeed += Permuter.PHI64, link.spawn_chance)) continue;
					FlowerFeature.Entry entry;
					builderb0y.bigglobe.randomSources.RandomSource radiusSource;
					boolean randomized = Permuter.nextChancedBoolean(otherGridSeed += Permuter.PHI64, link.randomize_chance);
					if (randomized) {
						int index = randomIndex(weights, Permuter.permute(otherGridSeed += Permuter.PHI64, x, z));
						if (index < 0) continue;
						entry = elements.get(index);
						radiusSource = link.randomize_radius;
					}
					else {
						int index = randomIndex(weights, Permuter.stafford(otherGridSeed += Permuter.PHI64));
						if (index < 0) continue;
						entry = elements.get(index);
						radiusSource = entry.radius();
					}
					double radius = radiusSource.get(column, y, Permuter.stafford(otherGridSeed += Permuter.PHI64));
					double centerX = Permuter.nextPositiveDouble(otherGridSeed += Permuter.PHI64) * variation + offsetX;
					double centerZ = Permuter.nextPositiveDouble(otherGridSeed += Permuter.PHI64) * variation + offsetZ;
					double distanceSquared = BigGlobeMath.squareD(inGridX - centerX, inGridZ - centerZ) / BigGlobeMath.squareD(radius);
					double chance = noise - distanceSquared * maxNoise;
					if (collect) {
						if (chance > 0.0D && c.candidates < c.candidateDecoration.length) {
							int decoration = s.flowerDecoration(entry, Permuter.permute(s.flowerSeed ^ 0x9A99AA4557D5FE0FL, x, y, z) ^ 0x61425E46B2DFE013L);
							c.candidateDecoration[c.candidates] = decoration;
							c.candidateChance[c.candidates] = Math.min(chance, 1.0D);
							c.candidates++;
						}
						continue;
					}
					if (Permuter.nextChancedBoolean(overlapSeed += Permuter.PHI64, chance)) {
						if (overlapChance++ == 0 || Permuter.nextBoundedInt(overlapSeed += Permuter.PHI64, overlapChance) == 0) {
							chosen = entry;
						}
					}
				}
			}
		}
		if (chosen == null) return 0;
		long placeSeed = Permuter.permute(s.flowerSeed ^ 0x9A99AA4557D5FE0FL, x, y, z) ^ 0x61425E46B2DFE013L;
		return s.flowerDecoration(chosen, placeSeed);
	}

	/** Collects the flower patches around a coarse cell once per column. */
	private void collectFlowerPatches(ScriptedColumn column) {
		Context c = this.context;
		c.candidates = 0;
		this.flowerAt(column, column.x(), column.z(), true);
		double none = 1.0D;
		for (int i = 0; i < c.candidates; i++) none *= 1.0D - c.candidateChance[i];
		c.flowerChance = 1.0D - none;
	}

	private boolean flowerPatchesCollected;

	/** Decides the decoration of the block (x, z). */
	private int decide(ScriptedColumn column, int x, int z, boolean exactFlowers) {
		Shared s = this.shared;
		Context c = this.context;
		Draws draws = this.draws;
		draws.start(s.seed, x, z);

		// bushes are placed first (large features), then flowers, then small foliage and petals, then sticks and rocks
		if (c.bushChance > 0.0D && draws.next() < c.bushChance) return c.bushDecoration;

		if (c.flowerY != Integer.MIN_VALUE) {
			int flower;
			if (exactFlowers) {
				flower = this.flowerAt(column, x, z, false);
			}
			else {
				if (!this.flowerPatchesCollected) {
					this.collectFlowerPatches(column);
					this.flowerPatchesCollected = true;
				}
				flower = 0;
				if (c.flowerChance > 0.0D && draws.next() < c.flowerChance) {
					// pick a patch in proportion to its chance
					double total = 0.0D;
					for (int i = 0; i < c.candidates; i++) total += c.candidateChance[i];
					double pick = draws.next() * total;
					for (int i = 0; i < c.candidates; i++) {
						pick -= c.candidateChance[i];
						if (pick < 0.0D || i == c.candidates - 1) {
							flower = c.candidateDecoration[i];
							break;
						}
					}
				}
			}
			if (flower != 0 && this.canGrow(flower)) return flower;
		}

		return smallDecoration(draws, c, s.codes);
	}

	/**
	 * Big Globe's per-block decorators in the order they run: surface_small_foliage (grass, ferns, dead bushes,
	 * dry grass, spores), cherry_blossoms (pink petals, leaf litter, wildflowers, which replace the grass) and
	 * surface_materials (sticks and rocks, which replace grass but not flowers).
	 */
	static int smallDecoration(Draws draws, Context c, Codes k) {
		int result = 0;
		double a = c.adjustedFoliage;
		if (draws.next() < a) {
			switch (c.ground) {
				case MUD -> {}
				case MYCELIUM -> {
					if (c.mushrooms && draws.next() < a * 0.0625D) result = k.mushroomSpores;
				}
				case BLACKSTONE -> {
					if (c.charred) result = k.charredGrass;
				}
				default -> {
					if (draws.next() < c.deadBushChance) {
						if (c.deadBushesAllowed && c.ground != OTHER) result = k.deadBush;
					}
					else if (draws.next() < a) {
						if (c.ground == SAND) {
							if (c.hot) {
								boolean tall = draws.next() < a;
								if (draws.next() < 0.015625D) result = tall ? k.tallDryGrass : k.shortDryGrass;
							}
						}
						else if (c.grassy && c.ground == SOIL) {
							boolean fern = draws.next() < c.fernChance;
							if (draws.next() < a) result = fern ? k.largeFern : k.tallGrass;
							else result = fern ? k.fern : k.shortGrass;
						}
					}
					else if (c.grassy && c.ground == SOIL) {
						result = k.bigGlobeShortGrass;
					}
				}
			}
		}

		if (c.cherryTotal > 0.0D && c.ground == SOIL) {
			double pick = draws.next() * c.cherryTotal;
			double weight;
			int kind;
			if ((pick -= c.petalWeight) < 0.0D) {
				weight = c.petalWeight;
				kind = k.pinkPetals;
			}
			else if ((pick -= c.litterWeight) < 0.0D) {
				weight = c.litterWeight;
				kind = k.leafLitter;
			}
			else {
				weight = c.wildWeight;
				kind = c.wildKind;
			}
			if (kind != 0 && draws.next() < weight * c.cold) result = kind;
		}

		if (c.stickChance > 0.0D && c.ground != SAND && draws.next() < c.stickChance && k.replaceable.test(result)) result = k.stick;
		if (c.rockChance > 0.0D && draws.next() < c.rockChance && k.replaceable.test(result)) result = k.rock;
		return result;
	}

	private boolean canGrow(int decoration) {
		Decoration d = this.shared.table.get(decoration);
		byte ground = this.context.ground;
		if (d.under != null) return d.underReplaces == null || ground == SOIL;
		return !d.needsSoil || ground == SOIL;
	}

	/** Which of Big Globe's four wildflowers grows here, from two noises like its cherry_blossoms feature. */
	private int wildflowerKind(int x, int z) {
		Shared s = this.shared;
		double noise1 = SmoothNoise.sample(s.seed ^ 0x57A1_D_F10E_5001L, x, z);
		double noise2 = SmoothNoise.sample(s.seed ^ 0x57A1_D_F10E_5002L, x, z);
		double gap = 0.125D;
		if (noise1 > gap) {
			if (noise2 > gap) return s.wildflowers;
			if (noise2 < -gap) return s.redWildflowers;
		}
		else if (noise1 < -gap) {
			if (noise2 > gap) return s.bluebonnets;
			if (noise2 < -gap) return s.violets;
		}
		return 0;
	}

	/**
	 * Puts the decided ground cover on the section's columns, after structures and tree canopies were laid.
	 * A decoration only goes where no structure covers the column and the blocks above the ground are still air.
	 */
	void apply() {
		List<Decoration> table = this.shared.table;
		for (int index = 0; index < this.decision.length; index++) {
			int choice = this.decision[index];
			if (choice == 0) continue;
			Decoration decoration = table.get(choice);
			// structures were placed first, as in Big Globe; nothing grows on what they built
			if (this.sampler.occupied(index / SectionSampler.WIDTH, index % SectionSampler.WIDTH)) continue;
			ColumnProfile profile = this.sampler.profile(index / SectionSampler.WIDTH, index % SectionSampler.WIDTH);
			int y = this.groundTop[index];
			int height = decoration.upper != null ? 2 : 1;
			if (decoration.bush && ((CanopyModel.mix(this.shared.seed, index, y) >>> 20) & 3) == 0) height = 2;
			if (!airAt(profile, y, y + height)) continue;
			int sky = CanopyModel.skyAt(profile, y);
			if (decoration.under != null) {
				int groundRun = profile.runAt(y - 1);
				if (groundRun < 0) continue;
				BlockState groundState = profile.states[groundRun];
				if (decoration.underReplaces != null && !decoration.underReplaces.contains(groundState)) continue;
				profile.overlay(decoration.under, y - 1, y, profile.skyLight[groundRun], decoration.under.getLightEmission());
			}
			if (decoration.bush) {
				profile.overlay(decoration.lower, y, y + height, sky, 0);
			}
			else {
				profile.overlay(decoration.lower, y, y + 1, sky, decoration.lower.getLightEmission());
				if (decoration.upper != null) profile.overlay(decoration.upper, y + 1, y + 2, sky, decoration.upper.getLightEmission());
			}
		}
	}

	static boolean airAt(ColumnProfile profile, int from, int to) {
		for (int y = from; y < to; y++) {
			int run = profile.runAt(y);
			if (run < 0 || !profile.states[run].isAir()) return false;
		}
		return true;
	}

	static double number(@Nullable Object value) {
		return value instanceof Number number ? number.doubleValue() : 0.0D;
	}

	static double clamp01(double value) {
		return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
	}

	/** Big Globe's {@code unmixSmooth(a, b, x)}: 0 at a, 1 at b, smoothstep in between. */
	static double unmixSmooth(double from, double to, double value) {
		return Interpolator.unmixSmooth(from, to, value);
	}

	/** {@code value > 0 ? value / (value + coefficient) : 0} from Big Globe's cherry_blossoms feature. */
	static double curve(double value, double coefficient) {
		return value > 0.0D ? value / (value + coefficient) : 0.0D;
	}
}
