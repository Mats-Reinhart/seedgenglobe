package dev.seedgenglobe.xaero;

import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import dev.seedgenglobe.terrain.ColumnProfile;

/**
 * Checks {@link XaeroPixel#toPixel} against the way Xaero's World Map reads a column ({@code MapWriter.loadPixel}
 * with {@code loadPixelHelp} and {@code OverlayBuilder}, read from Xaero's World Map 1.47.2):
 * <ul>
 * <li>blocks Xaero does not draw are passed over, see-through ones become overlays, the first other block is the
 * pixel, at its own height;</li>
 * <li>a fluid counts before the block it is in: a waterlogged block gets a water overlay, a fluid's own block is
 * not looked at twice, and an opaque fluid (lava) is the pixel itself;</li>
 * <li>consecutive blocks of one kind deepen one overlay, another kind starts the next (two are kept);</li>
 * <li>the top height is the highest overlay's, and a column that is see-through to the bottom is drawn as
 * overlays over air at the lowest height.</li>
 * </ul>
 * Runs on plain Java with small fakes of the Minecraft classes involved; see test-offline.sh.
 */
public final class XaeroPixelTest {

	static final int MIN = -64, MAX = 320;

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState STONE = Blocks.STONE.defaultBlockState();
	static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
	static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
	static final BlockState SAND = Blocks.SAND.defaultBlockState();
	static final BlockState WATER = Blocks.WATER.defaultBlockState();
	static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
	static final BlockState SHORT_GRASS = new Block("short_grass", false, false, 0, 0).defaultBlockState();
	static final BlockState TORCH = new Block("torch", false, false, 14, 0).defaultBlockState();
	static final BlockState GLASS = new Block("glass", false, false, 0, 0).defaultBlockState();
	static final BlockState ICE = new Block("ice", false, false, 0, 1).defaultBlockState();
	static final BlockState TINTED_GLASS = new Block("stained_glass", false, false, 0, 0).defaultBlockState();
	static final BlockState GRAVEL = new Block("gravel", false, false, 0, 15).defaultBlockState();
	static final BlockState LAVA = new Block("lava", false, true, 15, 0).defaultBlockState();
	static final BlockState WATERLOGGED_LEAVES = new Block("waterlogged_leaves", false, true, 0, 1).defaultBlockState();
	static {
		WATERLOGGED_LEAVES.fluidBlock = WATER;
	}

	static final ResourceKey<Biome> PLAINS = new ResourceKey<>("plains");
	static final Holder<Biome> PLAINS_HOLDER = new Holder<>() {

		@Override
		public Biome value() {
			return new Biome("plains");
		}

		@Override
		public Optional<ResourceKey<Biome>> unwrapKey() {
			return Optional.of(PLAINS);
		}
	};

	/** Xaero's rules for the blocks above: grass, torches and glass are not drawn, water, ice and stained glass are see-through. */
	static final XaeroPixel.Rules RULES = new XaeroPixel.Rules() {

		final Set<String> skip = Set.of("short_grass", "torch", "glass");
		final Set<String> overlay = Set.of("ice", "stained_glass");

		@Override
		public byte classify(BlockState state) {
			if (this.skip.contains(state.name)) return SKIP;
			if (this.overlay.contains(state.name)) return OVERLAY;
			return OPAQUE;
		}

		@Override
		public byte classifyFluid(FluidState fluid, BlockState fluidBlock) {
			return fluidBlock == LAVA ? OPAQUE : OVERLAY;
		}
	};

	static int checks, failures;

	public static void main(String[] args) {
		grassland();
		ocean();
		frozenOcean();
		lavaLake();
		waterloggedLeaves();
		glassOverStone();
		bottomlessWater();
		emptyColumn();
		splitWaterRuns();
		thirdOverlayLeftOut();
		lightFromAbove();
		System.out.println("XaeroPixelTest: " + checks + " checks, " + failures + " failures");
		if (failures > 0) System.exit(1);
	}

	// ------------------------------------------------------------------------------------------------- scenarios

	static void grassland() {
		MapPixel pixel = pixel(
			run(AIR, 65, MAX),
			run(SHORT_GRASS, 64, 65),
			run(GRASS, 63, 64),
			run(DIRT, 60, 63),
			run(STONE, MIN, 60)
		);
		expect("grassland: short grass is not drawn, the grass block is", pixel.state == GRASS);
		expect("grassland: height of the grass block itself", pixel.height == 63);
		expect("grassland: top height is the block's", pixel.topHeight == 63);
		expect("grassland: no overlay", pixel.overlay == null && pixel.overlay2 == null);
		expect("grassland: biome of the column", pixel.biome == PLAINS);
	}

	static void ocean() {
		MapPixel pixel = pixel(
			run(AIR, 63, MAX),
			run(WATER, 50, 63),
			run(SAND, 45, 50),
			run(STONE, MIN, 45)
		);
		expect("ocean: sea floor is the pixel", pixel.state == SAND && pixel.height == 49);
		expect("ocean: water overlay 13 deep", pixel.overlay == WATER && pixel.overlayDepth == 13);
		expect("ocean: top height is the water surface", pixel.topHeight == 62);
		expect("ocean: one overlay", pixel.overlay2 == null);
	}

	static void frozenOcean() {
		MapPixel pixel = pixel(
			run(AIR, 63, MAX),
			run(ICE, 62, 63),
			run(WATER, 50, 62),
			run(GRAVEL, 45, 50),
			run(STONE, MIN, 45)
		);
		expect("frozen ocean: ice first", pixel.overlay == ICE && pixel.overlayDepth == 1);
		expect("frozen ocean: water under the ice", pixel.overlay2 == WATER && pixel.overlayDepth2 == 12);
		expect("frozen ocean: sea floor", pixel.state == GRAVEL && pixel.height == 49);
		expect("frozen ocean: top height is the ice", pixel.topHeight == 62);
	}

	static void lavaLake() {
		MapPixel pixel = pixel(
			run(AIR, 41, MAX),
			run(LAVA, 30, 41),
			run(STONE, MIN, 30)
		);
		expect("lava: drawn itself, at its surface", pixel.state == LAVA && pixel.height == 40 && pixel.topHeight == 40);
		expect("lava: no overlay", pixel.overlay == null);
	}

	static void waterloggedLeaves() {
		MapPixel pixel = pixel(
			run(AIR, 71, MAX),
			run(WATERLOGGED_LEAVES, 70, 71),
			run(LOG, 64, 70),
			run(GRASS, 63, 64),
			run(STONE, MIN, 63)
		);
		expect("waterlogged leaves: the water is an overlay", pixel.overlay == WATER && pixel.overlayDepth == 1);
		expect("waterlogged leaves: the leaves are the pixel", pixel.state == WATERLOGGED_LEAVES && pixel.height == 70);
		expect("waterlogged leaves: top height", pixel.topHeight == 70);
	}

	static void glassOverStone() {
		MapPixel pixel = pixel(
			run(AIR, 81, MAX),
			run(GLASS, 80, 81),
			run(STONE, MIN, 80)
		);
		expect("glass is not drawn", pixel.state == STONE && pixel.height == 79 && pixel.overlay == null);
	}

	static void bottomlessWater() {
		MapPixel pixel = pixel(run(WATER, MIN, MAX));
		expect("bottomless water: overlay over air", pixel.state == AIR && pixel.overlay == WATER);
		expect("bottomless water: at the lowest height", pixel.height == MIN);
		expect("bottomless water: full depth", pixel.overlayDepth == MAX - MIN);
		expect("bottomless water: top height is the surface", pixel.topHeight == MAX - 1);
	}

	static void emptyColumn() {
		MapPixel pixel = pixel(run(AIR, MIN, MAX));
		expect("empty column: nothing to draw", pixel.state == null);
	}

	static void splitWaterRuns() {
		ColumnProfile profile = new ColumnProfile();
		profile.clear();
		profile.add(AIR, 63, MAX, 15, 0);
		profile.add(WATER, 60, 63, 15, 0);
		profile.add(WATER, 55, 60, 12, 0); // a different sky light keeps the runs apart
		profile.add(SAND, MIN, 55, 0, 0);
		profile.findSurface(MIN);
		profile.biome = PLAINS_HOLDER;
		expect("split water: two runs in the profile", profile.count == 4);
		MapPixel pixel = new MapPixel();
		XaeroPixel.toPixel(profile, RULES, pixel);
		expect("split water: one overlay of both runs", pixel.overlay == WATER && pixel.overlayDepth == 8 && pixel.overlay2 == null);
		expect("split water: floor", pixel.state == SAND && pixel.height == 54);
	}

	static void thirdOverlayLeftOut() {
		MapPixel pixel = pixel(
			run(AIR, 63, MAX),
			run(ICE, 62, 63),
			run(WATER, 58, 62),
			run(TINTED_GLASS, 57, 58),
			run(WATER, 50, 57),
			run(SAND, MIN, 50)
		);
		expect("three kinds: ice first", pixel.overlay == ICE && pixel.overlayDepth == 1);
		expect("three kinds: the third kind is left out, water keeps adding up", pixel.overlay2 == WATER && pixel.overlayDepth2 == 11);
		expect("three kinds: floor", pixel.state == SAND && pixel.height == 49);
	}

	static void lightFromAbove() {
		MapPixel pixel = pixel(
			run(AIR, 65, MAX),
			new Run(TORCH, 64, 65, 14),
			run(GRASS, 63, 64),
			run(STONE, MIN, 63)
		);
		expect("light: torch not drawn", pixel.state == GRASS);
		expect("light: light given off above the pixel", pixel.light == 14);
	}

	// ------------------------------------------------------------------------------------------------- helpers

	record Run(BlockState state, int bottom, int top, int light) {}

	static Run run(BlockState state, int bottom, int top) {
		return new Run(state, bottom, top, state.getLightEmission());
	}

	/** A profile of these runs, given top-down. */
	static MapPixel pixel(Run... runs) {
		ColumnProfile profile = new ColumnProfile();
		profile.clear();
		for (Run run : runs) profile.add(run.state(), run.bottom(), run.top(), 15, run.light());
		profile.findSurface(MIN);
		profile.biome = PLAINS_HOLDER;
		MapPixel pixel = new MapPixel();
		XaeroPixel.toPixel(profile, RULES, pixel);
		return pixel;
	}

	static void expect(String what, boolean ok) {
		checks++;
		if (!ok) {
			failures++;
			System.out.println("FAILED: " + what);
		}
	}
}
