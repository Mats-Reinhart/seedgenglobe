package dev.seedgenglobe.xaero;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The surface map of one Xaero region (512 x 512 blocks) at one LOD detail level: every cell holds what Xaero's
 * World Map would record for the top of that column, i.e. the block it shows, its height, the water (or ice over
 * water) above it and the biome. A cell covers 2^detail x 2^detail blocks.
 * <p>
 * Immutable once built; built and read on background threads, read by the map writer on the render thread.
 */
public final class LodRegion {

	public static final int REGION_BLOCKS = 512;
	static final int FORMAT = 2;
	static final short EMPTY = -1;

	public final int regionX, regionZ, detail, cells;
	final BlockState[] statePalette;
	final ResourceKey<Biome>[] biomePalette;
	/** Index into {@link #statePalette}, or {@link #EMPTY} when the column could not be computed. */
	final short[] state;
	final short[] height;
	final short[] topHeight;
	/** Index into {@link #biomePalette}, -1 for none. */
	final short[] biome;
	/** Index into {@link #statePalette} of the see-through block above the surface (water, ice), -1 for none. */
	final short[] overlay;
	/** Depth of that overlay in blocks, capped at 255. */
	final byte[] overlayDepth;
	/** The see-through block under the first one (water under ice), -1 for none, and its depth. */
	final short[] overlay2;
	final byte[] overlayDepth2;
	final byte[] light;

	LodRegion(int regionX, int regionZ, int detail, BlockState[] statePalette, ResourceKey<Biome>[] biomePalette, short[] state, short[] height, short[] topHeight, short[] biome, short[] overlay, byte[] overlayDepth, short[] overlay2, byte[] overlayDepth2, byte[] light) {
		this.regionX = regionX;
		this.regionZ = regionZ;
		this.detail = detail;
		this.cells = REGION_BLOCKS >> detail;
		this.statePalette = statePalette;
		this.biomePalette = biomePalette;
		this.state = state;
		this.height = height;
		this.topHeight = topHeight;
		this.biome = biome;
		this.overlay = overlay;
		this.overlayDepth = overlayDepth;
		this.overlay2 = overlay2;
		this.overlayDepth2 = overlayDepth2;
		this.light = light;
	}

	/** Cell index of the block at (x, z) relative to the region's corner (0..511). */
	int cell(int localX, int localZ) {
		return (localX >> this.detail) * this.cells + (localZ >> this.detail);
	}

	boolean empty(int cell) {
		return this.state[cell] == EMPTY;
	}

	BlockState stateAt(int cell) {
		return this.statePalette[this.state[cell]];
	}

	@Nullable BlockState overlayAt(int cell) {
		short index = this.overlay[cell];
		return index < 0 ? null : this.statePalette[index];
	}

	@Nullable BlockState overlay2At(int cell) {
		short index = this.overlay2[cell];
		return index < 0 ? null : this.statePalette[index];
	}

	@Nullable ResourceKey<Biome> biomeAt(int cell) {
		short index = this.biome[cell];
		return index < 0 ? null : this.biomePalette[index];
	}

	public static long key(int regionX, int regionZ) {
		return ((long)(regionX) & 0xFFFFFFFFL) | ((long)(regionZ) << 32);
	}

	public static int keyX(long key) {
		return (int)(key);
	}

	public static int keyZ(long key) {
		return (int)(key >> 32);
	}

	// ------------------------------------------------------------------------------------------------ building

	/** Collects cells; palettes grow as states and biomes are added. Not thread safe. */
	static final class Builder {

		final int regionX, regionZ, detail, cells;
		final Map<BlockState, Short> states = new HashMap<>();
		final List<BlockState> statePalette = new ArrayList<>();
		final Map<ResourceKey<Biome>, Short> biomes = new HashMap<>();
		final List<ResourceKey<Biome>> biomePalette = new ArrayList<>();
		final short[] state, height, topHeight, biome, overlay, overlay2;
		final byte[] overlayDepth, overlayDepth2, light;

		Builder(int regionX, int regionZ, int detail) {
			this.regionX = regionX;
			this.regionZ = regionZ;
			this.detail = detail;
			this.cells = REGION_BLOCKS >> detail;
			int size = this.cells * this.cells;
			this.state = new short[size];
			this.height = new short[size];
			this.topHeight = new short[size];
			this.biome = new short[size];
			this.overlay = new short[size];
			this.overlayDepth = new byte[size];
			this.overlay2 = new short[size];
			this.overlayDepth2 = new byte[size];
			this.light = new byte[size];
			java.util.Arrays.fill(this.state, EMPTY);
			java.util.Arrays.fill(this.biome, (short)(-1));
			java.util.Arrays.fill(this.overlay, (short)(-1));
			java.util.Arrays.fill(this.overlay2, (short)(-1));
		}

		short stateIndex(BlockState blockState) {
			Short index = this.states.get(blockState);
			if (index == null) {
				index = (short)(this.statePalette.size());
				this.statePalette.add(blockState);
				this.states.put(blockState, index);
			}
			return index;
		}

		short biomeIndex(@Nullable ResourceKey<Biome> key) {
			if (key == null) return -1;
			Short index = this.biomes.get(key);
			if (index == null) {
				index = (short)(this.biomePalette.size());
				this.biomePalette.add(key);
				this.biomes.put(key, index);
			}
			return index;
		}

		void set(int cellX, int cellZ, MapPixel pixel) {
			int cell = cellX * this.cells + cellZ;
			if (pixel.state == null) {
				this.state[cell] = EMPTY;
				return;
			}
			this.state[cell] = this.stateIndex(pixel.state);
			this.height[cell] = (short)(pixel.height);
			this.topHeight[cell] = (short)(pixel.topHeight);
			this.biome[cell] = this.biomeIndex(pixel.biome);
			this.overlay[cell] = pixel.overlay != null ? this.stateIndex(pixel.overlay) : -1;
			this.overlayDepth[cell] = (byte)(Math.min(pixel.overlayDepth, 255));
			this.overlay2[cell] = pixel.overlay != null && pixel.overlay2 != null ? this.stateIndex(pixel.overlay2) : -1;
			this.overlayDepth2[cell] = (byte)(Math.min(pixel.overlayDepth2, 255));
			this.light[cell] = pixel.light;
		}

		@SuppressWarnings("unchecked")
		LodRegion build() {
			return new LodRegion(
				this.regionX, this.regionZ, this.detail,
				this.statePalette.toArray(new BlockState[0]),
				this.biomePalette.toArray(new ResourceKey[0]),
				this.state, this.height, this.topHeight, this.biome, this.overlay, this.overlayDepth, this.overlay2, this.overlayDepth2, this.light
			);
		}
	}

	// ------------------------------------------------------------------------------------------------ storage

	void write(DataOutputStream out) throws IOException {
		out.writeInt(FORMAT);
		out.writeInt(this.regionX);
		out.writeInt(this.regionZ);
		out.writeByte(this.detail);
		out.writeShort(this.statePalette.length);
		for (BlockState blockState : this.statePalette) out.writeUTF(stateToString(blockState));
		out.writeShort(this.biomePalette.length);
		for (ResourceKey<Biome> key : this.biomePalette) out.writeUTF(key.identifier().toString());
		int size = this.cells * this.cells;
		for (int i = 0; i < size; i++) {
			out.writeShort(this.state[i]);
			if (this.state[i] == EMPTY) continue;
			out.writeShort(this.height[i]);
			out.writeShort(this.topHeight[i]);
			out.writeShort(this.biome[i]);
			out.writeShort(this.overlay[i]);
			out.writeByte(this.overlayDepth[i]);
			out.writeShort(this.overlay2[i]);
			out.writeByte(this.overlayDepth2[i]);
			out.writeByte(this.light[i]);
		}
	}

	/** @return null when the data was written by another format version or names blocks that no longer exist */
	@SuppressWarnings("unchecked")
	static @Nullable LodRegion read(DataInputStream in, int expectedX, int expectedZ) throws IOException {
		if (in.readInt() != FORMAT) return null;
		int regionX = in.readInt(), regionZ = in.readInt();
		if (regionX != expectedX || regionZ != expectedZ) return null;
		int detail = in.readByte();
		if (detail < 0 || detail > 9) return null;
		int stateCount = in.readUnsignedShort();
		BlockState[] states = new BlockState[stateCount];
		for (int i = 0; i < stateCount; i++) {
			BlockState blockState = stateFromString(in.readUTF());
			if (blockState == null) return null;
			states[i] = blockState;
		}
		int biomeCount = in.readUnsignedShort();
		ResourceKey<Biome>[] biomes = new ResourceKey[biomeCount];
		for (int i = 0; i < biomeCount; i++) {
			Identifier identifier = Identifier.tryParse(in.readUTF());
			if (identifier == null) return null;
			biomes[i] = ResourceKey.create(Registries.BIOME, identifier);
		}
		int cells = REGION_BLOCKS >> detail;
		int size = cells * cells;
		short[] state = new short[size], height = new short[size], topHeight = new short[size], biome = new short[size], overlay = new short[size], overlay2 = new short[size];
		byte[] overlayDepth = new byte[size], overlayDepth2 = new byte[size], light = new byte[size];
		for (int i = 0; i < size; i++) {
			state[i] = in.readShort();
			if (state[i] == EMPTY) {
				biome[i] = -1;
				overlay[i] = -1;
				overlay2[i] = -1;
				continue;
			}
			if (state[i] < 0 || state[i] >= stateCount) return null;
			height[i] = in.readShort();
			topHeight[i] = in.readShort();
			biome[i] = in.readShort();
			overlay[i] = in.readShort();
			overlayDepth[i] = in.readByte();
			overlay2[i] = in.readShort();
			overlayDepth2[i] = in.readByte();
			light[i] = in.readByte();
			if (biome[i] >= biomeCount || overlay[i] >= stateCount || overlay2[i] >= stateCount) return null;
		}
		return new LodRegion(regionX, regionZ, detail, states, biomes, state, height, topHeight, biome, overlay, overlayDepth, overlay2, overlayDepth2, light);
	}

	/** "namespace:block[property=value,...]" with properties in the block's own order. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static String stateToString(BlockState blockState) {
		StringBuilder builder = new StringBuilder(String.valueOf(BuiltInRegistries.BLOCK.getKey(blockState.getBlock())));
		boolean first = true;
		for (Object object : (Iterable<?>)(Object)(blockState.getProperties())) {
			if (!(object instanceof Property<?> property)) continue;
			builder.append(first ? '[' : ',');
			first = false;
			builder.append(property.getName()).append('=').append(((Property)(property)).getName(blockState.getValue(property)));
		}
		if (!first) builder.append(']');
		return builder.toString();
	}

	static @Nullable BlockState stateFromString(String text) {
		int bracket = text.indexOf('[');
		String id = bracket < 0 ? text : text.substring(0, bracket);
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) return null;
		Object value = BuiltInRegistries.BLOCK.getValue(identifier);
		if (!(value instanceof Block block) || (block == Blocks.AIR && !identifier.getPath().equals("air"))) return null;
		BlockState blockState = block.defaultBlockState();
		if (bracket >= 0 && text.endsWith("]")) {
			for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
				int equals = pair.indexOf('=');
				if (equals > 0) blockState = dev.seedgenglobe.terrain.BlockStates.with(blockState, pair.substring(0, equals), pair.substring(equals + 1));
			}
		}
		return blockState;
	}
}
