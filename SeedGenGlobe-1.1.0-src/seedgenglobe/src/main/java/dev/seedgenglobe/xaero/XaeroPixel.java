package dev.seedgenglobe.xaero;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import dev.seedgenglobe.terrain.ColumnProfile;

/**
 * Reads a terrain column the way Xaero's World Map reads a chunk column ({@code MapWriter.loadPixel}): walking down
 * from the top, blocks Xaero does not draw (short grass, flowers when they are off, glass, torches) are skipped,
 * see-through blocks (water, ice) stack up as overlays, and the first block with a map colour is the pixel. A fluid
 * is looked at before the block it sits in, so waterlogged blocks get their water drawn over them.
 */
final class XaeroPixel {

	/** How a block looks to Xaero's map. */
	interface Rules {

		byte OPAQUE = 0, OVERLAY = 1, SKIP = 2;

		/** Drawn, drawn as a see-through overlay, or not drawn at all. */
		byte classify(BlockState state);

		/** The same for a fluid, given the block Xaero draws for it. */
		byte classifyFluid(FluidState fluid, BlockState fluidBlock);
	}

	private XaeroPixel() {}

	static void toPixel(ColumnProfile profile, Rules rules, MapPixel out) {
		out.clear();
		int overlayTop = Integer.MIN_VALUE;
		boolean found = false;
		for (int run = 0; run < profile.count && !found; run++) {
			BlockState state = profile.states[run];
			if (state.isAir()) continue;
			int top = profile.tops[run] - 1;
			int depth = profile.tops[run] - profile.bottoms[run];
			FluidState fluid = state.getFluidState();
			if (!fluid.isEmpty()) {
				BlockState fluidBlock = fluid.createLegacyBlock();
				byte kind = rules.classifyFluid(fluid, fluidBlock);
				if (kind == Rules.OPAQUE) {
					// lava: Xaero draws the block the fluid is in
					setSurface(out, state, top, profile, run);
					found = true;
					continue;
				}
				if (kind == Rules.OVERLAY) overlayTop = addOverlay(out, fluidBlock, depth, top, overlayTop, profile, run);
				if (state.getBlock() == fluidBlock.getBlock()) continue;
			}
			byte kind = rules.classify(state);
			if (kind == Rules.SKIP) continue;
			if (kind == Rules.OVERLAY) {
				overlayTop = addOverlay(out, state, depth, top, overlayTop, profile, run);
				continue;
			}
			setSurface(out, state, top, profile, run);
			found = true;
		}
		if (!found) {
			if (out.overlay == null) return; // nothing Xaero would draw: leave the cell empty
			// see-through down to the bottom of the world: Xaero draws the overlays over nothing at the lowest height
			out.state = Blocks.AIR.defaultBlockState();
			out.height = profile.count > 0 ? profile.bottoms[profile.count - 1] : 0;
		}
		out.topHeight = out.overlay != null ? overlayTop : out.height;
		out.biome = biomeKey(profile.biome);
	}

	private static void setSurface(MapPixel out, BlockState state, int top, ColumnProfile profile, int run) {
		out.state = state;
		out.height = top;
		if (out.overlay == null) out.light = lightAbove(profile, run);
	}

	/**
	 * Stacks a see-through block the way Xaero's {@code OverlayBuilder} does: blocks of the same kind directly below
	 * each other deepen one overlay, a different kind starts the next one. Two are kept, which covers water and ice
	 * over water.
	 *
	 * @return the height of the highest overlay
	 */
	private static int addOverlay(MapPixel out, BlockState state, int depth, int top, int overlayTop, ColumnProfile profile, int run) {
		if (out.overlay == null) {
			out.overlay = state;
			out.overlayDepth = depth;
			out.light = lightAbove(profile, run);
			return top;
		}
		if (out.overlay2 == null) {
			if (out.overlay == state) out.overlayDepth += depth;
			else {
				out.overlay2 = state;
				out.overlayDepth2 = depth;
			}
		}
		else if (out.overlay2 == state) out.overlayDepth2 += depth;
		return overlayTop;
	}

	@SuppressWarnings("unchecked")
	static @Nullable ResourceKey<Biome> biomeKey(@Nullable Holder<Biome> biome) {
		if (biome == null) return null;
		Object key = ((Optional<?>)(Object)(biome.unwrapKey())).orElse(null);
		return key instanceof ResourceKey<?> resourceKey ? (ResourceKey<Biome>)(resourceKey) : null;
	}

	/** Xaero records the block light of the block above what it draws; here the light given off by the run above. */
	static byte lightAbove(ColumnProfile profile, int run) {
		return run > 0 ? profile.blockLight[run - 1] : 0;
	}
}
