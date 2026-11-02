package dev.seedgenglobe.structures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry;

import builderb0y.bigglobe.chunkgen.BigGlobeScriptedChunkGenerator;
import builderb0y.bigglobe.columns.ScriptedColumn.ColumnUsage;
import builderb0y.bigglobe.overriders.Overrider.SortedOverriders;
import builderb0y.bigglobe.structures.DelegatingStructure;
import builderb0y.bigglobe.structures.SizedStructure;
import builderb0y.bigglobe.structures.management.SmartStructurePlacement;
import builderb0y.bigglobe.structures.management.StructureLocator;
import builderb0y.bigglobe.structures.placement.ScriptedStructurePlacement;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.structures.StructureIndex.Candidate;
import dev.seedgenglobe.structures.StructureIndex.Placement;
import dev.seedgenglobe.terrain.SectionSampler;

/**
 * Big Globe's structures for one server level's LODs: where they are ({@link StructureIndex}) and how they change
 * the terrain and what they place ({@link StructureFrame}, one per sampler).
 * <p>
 * Everything happens the way Big Globe's chunk generator does it, on the generator threads, with its LOD hints:
 * structure starts come from the structure sets' own placements and Big Globe's structure and collision overriders,
 * the terrain around them is shaped by its column value overriders, raw-generation scripts (obelisks, nether
 * pillars, ...) and then every structure piece are placed, all into the section being generated instead of into
 * chunks. With LOD hints, Big Globe itself leaves underground structures out (its {@code #bigglobe:underground}
 * tags), so their structure sets are not even looked at.
 * <p>
 * Structures need the world seed, so they exist on the server and in singleplayer only, not for LODs a multiplayer
 * client generates from what Big Globe syncs.
 */
public final class LodStructures {

	/**
	 * Structure sets are generated at the detail levels their size calls for: every set up to the configured detail,
	 * sets that may reach 192 blocks from their position (lakes, mega trees) two levels further, and sets that may
	 * reach 256 blocks (Big Globe's obelisks) at every level.
	 */
	static final int TIERS = 3;
	static final int LARGE_RADIUS = 192, GIANT_RADIUS = 256, LARGE_EXTRA_DETAIL = 2;
	private static final int DETAILED_FAILURES = 8;

	final ServerLevel level;
	final WorldGenLevel levelAccess;
	final BigGlobeScriptedChunkGenerator generator;
	final StructureLocator.Context locatorContext;
	final LodStructureLocator locator;
	final SortedOverriders overriders;
	final Registry<Structure> structureRegistry;
	final StructureIndex index;
	final String name;
	final int levelMinY, levelMaxY;
	final long columnSeed;
	final int fullDetail;
	final @Nullable DifficultyInstance difficulty;
	private final Climate.Sampler sampler;
	private volatile @Nullable PalettedContainerFactory containers;

	final AtomicLong sections = new AtomicLong(), sectionsWithBlocks = new AtomicLong(), pieces = new AtomicLong(), placeNanos = new AtomicLong();
	final AtomicLong placementFailures = new AtomicLong();
	final AtomicLong overriddenQuads = new AtomicLong(), overrideNanos = new AtomicLong();
	private final AtomicInteger reported = new AtomicInteger();
	/** The generator is closing: scripts start failing as the server takes its registries away, which is not news. */
	private volatile boolean closing;

	private LodStructures(ServerLevel level, BigGlobeScriptedChunkGenerator generator, String name, List<Placement> placements) {
		this.level = level;
		this.levelAccess = level;
		this.generator = generator;
		this.name = name;
		this.levelMinY = level.getMinY();
		this.levelMaxY = level.getMinY() + level.getHeight();
		this.columnSeed = generator.columnSeed;
		this.locatorContext = new StructureLocator.Context(generator, generator.configuredColumnFactory(level, ColumnUsage.GENERIC.dhHints(0)), level);
		this.locator = new LodStructureLocator(generator.structureLocator());
		this.overriders = generator.getOverriders();
		this.structureRegistry = StructureLocator.structureRegistry(level);
		this.fullDetail = GlobeConfig.get().structureDetail;
		this.sampler = level.getChunkSource().randomState().sampler();
		DifficultyInstance difficulty = null;
		try {
			difficulty = this.levelAccess.getCurrentDifficultyAt(new BlockPos(0, Math.max(this.levelMinY, 64), 0));
		}
		catch (RuntimeException | LinkageError error) {
			SeedGenGlobe.LOGGER.debug("SeedGenGlobe: no difficulty for structure mobs", error);
		}
		this.difficulty = difficulty;
		this.index = new StructureIndex(this, placements.toArray(new Placement[0]));
	}

	/**
	 * @return null when structures are turned off (in SeedGenGlobe's config or for the world) or Big Globe has none here
	 */
	public static @Nullable LodStructures create(ServerLevel level, BigGlobeScriptedChunkGenerator generator, String name) {
		if (!GlobeConfig.get().structures) return null;
		try {
			if (!generator.structuresEnabled) {
				SeedGenGlobe.LOGGER.info("SeedGenGlobe: structures are turned off for {}, so LODs have none either.", name);
				return null;
			}
			ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
			if (state == null) state = generator.structureState;
			if (state == null) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: {} has no structure state yet, so LODs get no structures.", name);
				return null;
			}
			TagKey<Structure> lodlessStructures = TagKey.create(Registries.STRUCTURE, Identifier.parse("bigglobe:underground"));
			TagKey<?> lodlessTypes = TagKey.create(Registries.STRUCTURE_TYPE, Identifier.parse("bigglobe:underground"));
			List<Placement> placements = new ArrayList<>();
			List<String> left = new ArrayList<>();
			for (Object element : state.possibleStructureSets()) {
				@SuppressWarnings("unchecked")
				Holder<StructureSet> set = (Holder<StructureSet>)(element);
				StructureSet value = (StructureSet)(set.value());
				String setName = setName(set);
				if (!(value.placement() instanceof SmartStructurePlacement)) {
					left.add(setName);
					continue;
				}
				int radius = 0;
				boolean visible = false;
				for (Object selection : value.structures()) {
					@SuppressWarnings("unchecked")
					Holder<Structure> structure = (Holder<Structure>)(((StructureSelectionEntry)(selection)).structure());
					radius = Math.max(radius, radiusOf(structure));
					if (!lodless(structure, lodlessStructures, lodlessTypes)) visible = true;
				}
				if (!visible) {
					left.add(setName);
					continue;
				}
				int tier = radius >= GIANT_RADIUS ? 2 : (radius >= LARGE_RADIUS ? 1 : 0);
				placements.add(new Placement(placements.size(), set, setName, radius, tier, value.placement() instanceof ScriptedStructurePlacement));
			}
			if (placements.isEmpty()) {
				SeedGenGlobe.LOGGER.info("SeedGenGlobe: {} has no structures that show in LODs.", name);
				return null;
			}
			LodStructures structures = new LodStructures(level, generator, name, placements);
			SeedGenGlobe.LOGGER.info(
				"SeedGenGlobe: LODs of {} get {} structure sets up to detail {}, the {} large ones up to detail {} and the {} giant ones at every detail level{}",
				name,
				placements.size(),
				structures.fullDetail,
				structures.index.tiers[1].length,
				structures.fullDetail + LARGE_EXTRA_DETAIL,
				structures.index.tiers[2].length,
				left.isEmpty() ? "" : "; left out, as Big Globe does for LODs: " + String.join(", ", left)
			);
			return structures;
		}
		catch (RuntimeException | LinkageError error) {
			SeedGenGlobe.LOGGER.error("SeedGenGlobe could not set up structures for {}; LODs get none.", name, error);
			return null;
		}
	}

	static String setName(Holder<StructureSet> set) {
		Object key = set.unwrapKey().orElse(null);
		return key instanceof ResourceKey<?> resourceKey ? resourceKey.identifier().toString() : String.valueOf(set);
	}

	static int radiusOf(Holder<Structure> structure) {
		try {
			return ((SizedStructure)(structure.value())).bigglobe_getMaxRadiusInBlocks();
		}
		catch (RuntimeException | LinkageError error) {
			return 128;
		}
	}

	/**
	 * Big Globe's "underground" structure overrider removes these whenever its hints say LOD. Like the overrider, this
	 * looks at the structure a delegating structure (Big Globe's wrappers around vanilla ones) ends up at.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static boolean lodless(Holder<Structure> structure, TagKey<Structure> structures, TagKey types) {
		try {
			for (int depth = 0; depth < 16 && structure.value() instanceof DelegatingStructure delegating; depth++) {
				structure = delegating.delegate();
			}
			if (structure.is(structures)) return true;
			return BuiltInRegistries.STRUCTURE_TYPE.wrapAsHolder(((Structure)(structure.value())).type()).is(types);
		}
		catch (RuntimeException | LinkageError error) {
			return false;
		}
	}

	public StructureFrame newFrame(SectionSampler sampler) {
		return new StructureFrame(this, sampler);
	}

	/** Which structure sets a detail level gets: 0 = all of them, 1 = the large ones, 2 = the giant ones. */
	int tier(int detail) {
		if (detail <= this.fullDetail) return 0;
		return detail <= this.fullDetail + LARGE_EXTRA_DETAIL ? 1 : 2;
	}

	PalettedContainerFactory containers() {
		PalettedContainerFactory containers = this.containers;
		if (containers == null) {
			containers = this.containers = PalettedContainerFactory.create(this.level.registryAccess());
		}
		return containers;
	}

	Holder<Biome> biomeAt(int quartX, int quartY, int quartZ) {
		return this.generator.biome_source().getNoiseBiome(quartX, quartY, quartZ, this.sampler);
	}

	/** The generator closes: errors from here on come from the server going away and are not logged. */
	public void close() {
		this.closing = true;
	}

	void reportFailure(String what, Throwable error) {
		if (this.closing) return;
		int count = this.reported.incrementAndGet();
		if (count <= DETAILED_FAILURES) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe [{}]: structure error while {}", this.name, what, error);
		}
		else if (Integer.bitCount(count) == 1) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe [{}]: {} structure errors so far, latest while {}: {}", this.name, count, what, error.toString());
		}
	}

	public List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		long computed = this.index.computed.get();
		long sections = this.sections.get();
		lines.add(String.format(
			Locale.ROOT,
			"%s structures: %d sets (%d large, %d giant), %,d set-tiles computed (%.1f ms each), %,d starts found, %,d cached; %,d sections had structures nearby, %,d got blocks (%,d pieces, %.1f ms per section), terrain shaped around them in %,d column quads (%.1f us each)%s",
			this.name,
			this.index.all.length,
			this.index.tiers[1].length,
			this.index.tiers[2].length,
			computed,
			computed > 0 ? this.index.nanos.get() / 1.0e6D / computed : 0.0D,
			this.index.found.get(),
			this.index.cachedEntries(),
			sections,
			this.sectionsWithBlocks.get(),
			this.pieces.get(),
			sections > 0 ? this.placeNanos.get() / 1.0e6D / sections : 0.0D,
			this.overriddenQuads.get(),
			this.overriddenQuads.get() > 0 ? this.overrideNanos.get() / 1.0e3D / this.overriddenQuads.get() : 0.0D,
			this.index.failures.get() + this.placementFailures.get() > 0 ? ", " + (this.index.failures.get() + this.placementFailures.get()) + " errors (see the log)" : ""
		));
		return lines;
	}

	/**
	 * The structures SeedGenGlobe puts into LODs near a position, nearest first. Blocks while they are computed,
	 * so call it off the server thread.
	 *
	 * @param tier 0 for all structure sets, 1 for the large ones, 2 for the giant ones (what far LODs get)
	 */
	public List<String> describe(int x, int z, int radius, int tier, int limit) {
		List<Candidate> found = this.index.near(x - radius, z - radius, x + radius, z + radius, Math.max(0, Math.min(TIERS - 1, tier)), false);
		found.sort(Comparator.comparingLong(candidate -> distanceSquared(candidate, x, z)));
		List<String> lines = new ArrayList<>();
		for (Candidate candidate : found) {
			if (lines.size() == limit) break;
			long distance = Math.round(Math.sqrt(distanceSquared(candidate, x, z)));
			lines.add(String.format(
				Locale.ROOT,
				"%s at %d, %d, %d (%,d blocks, %d x %d x %d, %d pieces)",
				candidate.id,
				candidate.start.pos().getX(),
				candidate.start.pos().getY(),
				candidate.start.pos().getZ(),
				distance,
				candidate.box.maxX() - candidate.box.minX() + 1,
				candidate.box.maxY() - candidate.box.minY() + 1,
				candidate.box.maxZ() - candidate.box.minZ() + 1,
				candidate.start.start().getPieces().size()
			));
		}
		return lines;
	}

	static long distanceSquared(Candidate candidate, int x, int z) {
		long dx = Math.max(Math.max(candidate.box.minX() - x, x - candidate.box.maxX()), 0);
		long dz = Math.max(Math.max(candidate.box.minZ() - z, z - candidate.box.maxZ()), 0);
		return dx * dx + dz * dz;
	}

	public int fullDetail() {
		return this.fullDetail;
	}

	public String name() {
		return this.name;
	}
}
