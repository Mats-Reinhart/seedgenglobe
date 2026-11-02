package dev.seedgenglobe.xaero;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

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

/** How {@link LodMapStore} writes a {@link LodRegion}: palettes by name, then the cells. */
final class LodRegionCodec {

	static final int FORMAT = 3;

	private LodRegionCodec() {}

	static void write(LodRegion region, DataOutputStream out) throws IOException {
		out.writeInt(FORMAT);
		out.writeInt(region.regionX);
		out.writeInt(region.regionZ);
		out.writeByte(region.detail);
		out.writeLong(region.revision);
		out.writeShort(region.statePalette.length);
		for (BlockState blockState : region.statePalette) out.writeUTF(stateToString(blockState));
		out.writeShort(region.biomePalette.length);
		for (ResourceKey<Biome> key : region.biomePalette) out.writeUTF(key.identifier().toString());
		int size = region.size();
		for (int i = 0; i < size; i++) {
			out.writeShort(region.state[i]);
			if (region.state[i] == LodRegion.EMPTY) continue;
			out.writeShort(region.height[i]);
			out.writeShort(region.topHeight[i]);
			out.writeShort(region.biome[i]);
			out.writeShort(region.overlay[i]);
			out.writeByte(region.overlayDepth[i]);
			out.writeShort(region.overlay2[i]);
			out.writeByte(region.overlayDepth2[i]);
			out.writeByte(region.light[i]);
			out.writeByte(region.source[i]);
		}
	}

	/** @return null when the data was written by another format version or names blocks that no longer exist */
	@SuppressWarnings("unchecked")
	static @Nullable LodRegion read(DataInputStream in, int expectedX, int expectedZ) throws IOException {
		if (in.readInt() != FORMAT) return null;
		int regionX = in.readInt(), regionZ = in.readInt();
		if (regionX != expectedX || regionZ != expectedZ) return null;
		int detail = in.readByte();
		if (detail < 0 || detail > LodRegion.MAX_DETAIL) return null;
		long revision = in.readLong();
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
		int cells = LodRegion.REGION_BLOCKS >> detail;
		int size = cells * cells;
		short[] state = new short[size], height = new short[size], topHeight = new short[size], biome = new short[size], overlay = new short[size], overlay2 = new short[size];
		byte[] overlayDepth = new byte[size], overlayDepth2 = new byte[size], light = new byte[size], source = new byte[size];
		Arrays.fill(source, (byte)(LodRegion.NO_SOURCE));
		for (int i = 0; i < size; i++) {
			state[i] = in.readShort();
			if (state[i] == LodRegion.EMPTY) {
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
			source[i] = in.readByte();
			if (biome[i] >= biomeCount || overlay[i] >= stateCount || overlay2[i] >= stateCount) return null;
			if (source[i] < detail || source[i] >= LodRegion.NO_SOURCE) return null;
		}
		return new LodRegion(regionX, regionZ, detail, revision, states, biomes, state, height, topHeight, biome, overlay, overlayDepth, overlay2, overlayDepth2, light, source);
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
