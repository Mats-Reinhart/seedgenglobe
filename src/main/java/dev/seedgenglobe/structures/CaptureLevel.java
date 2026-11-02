package dev.seedgenglobe.structures;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;

import builderb0y.bigglobe.noise.MojangPermuter;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * The {@link WorldGenLevel} structure pieces are placed into: a dynamic proxy whose block reads and writes go to the
 * section being generated ({@link CaptureView}), much like Minecraft's WorldGenRegion during feature generation.
 * <p>
 * Minecraft calls structure pieces with such a region, so they only use what a region offers: blocks, heightmaps,
 * biomes, the level's registries and settings, and the occasional entity, block entity or block tick. Entities are
 * dropped, block entities do not exist and ticks never run, none of which a LOD can show. Nothing here touches the
 * real level's chunks, so pieces can be placed on any thread without loading anything.
 * <p>
 * A proxy instead of a class because WorldGenLevel and the interfaces it extends have well over a hundred methods,
 * most of them irrelevant here; those keep their default implementation or answer "nothing".
 */
final class CaptureLevel implements InvocationHandler {

	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	final LodStructures owner;
	final CaptureView view;
	private final WorldGenLevel world;
	private final Map<Long, ChunkAccess> chunks = new HashMap<>();
	private RandomSource random;

	CaptureLevel(LodStructures owner, CaptureView view) {
		this.owner = owner;
		this.view = view;
		this.world = (WorldGenLevel)(Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class }, this));
		this.random = new MojangPermuter(owner.columnSeed);
	}

	WorldGenLevel world() {
		return this.world;
	}

	void begin(long seed) {
		this.random = new MojangPermuter(seed);
		this.chunks.clear();
	}

	void end() {
		this.chunks.clear();
	}

	/** A throwaway chunk for pieces that mark blocks for post-processing; what is written to it is ignored. */
	private ChunkAccess chunk(int chunkX, int chunkZ) {
		long key = ChunkPos.pack(chunkX, chunkZ);
		ChunkAccess chunk = this.chunks.get(key);
		if (chunk == null) {
			chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, this.owner.level, this.owner.containers(), null);
			this.chunks.put(key, chunk);
		}
		return chunk;
	}

	private BlockState state(Object pos) {
		BlockPos blockPos = (BlockPos)(pos);
		return this.view.state(blockPos.getX(), blockPos.getY(), blockPos.getZ());
	}

	private void write(Object pos, Object state) {
		BlockPos blockPos = (BlockPos)(pos);
		this.view.write(blockPos.getX(), blockPos.getY(), blockPos.getZ(), (BlockState)(state));
	}

	private Holder<Biome> biome(int quartX, int quartY, int quartZ) {
		return this.owner.biomeAt(quartX, quartY, quartZ);
	}

	@Override
	@SuppressWarnings("unchecked")
	public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
		int count = args == null ? 0 : args.length;
		switch (method.getName()) {
			// --------------------------------------------------------------------------------------------- blocks
			case "getBlockState":
				if (count == 1) return this.state(args[0]);
				break;
			case "getFluidState":
				if (count == 1) return this.state(args[0]).getFluidState();
				break;
			case "isEmptyBlock":
				if (count == 1) return this.state(args[0]).isAir();
				break;
			case "isStateAtPosition":
				if (count == 2) return ((Predicate<BlockState>)(args[1])).test(this.state(args[0]));
				break;
			case "isFluidAtPosition":
				if (count == 2) return ((Predicate<Object>)(args[1])).test(this.state(args[0]).getFluidState());
				break;
			case "setBlock":
				if (count >= 2) {
					this.write(args[0], args[1]);
					return true;
				}
				break;
			case "removeBlock":
			case "destroyBlock":
				if (count >= 1 && args[0] instanceof BlockPos) {
					this.write(args[0], this.state(args[0]).getFluidState().createLegacyBlock());
					return true;
				}
				break;
			case "getBlockEntity":
				return count == 1 ? null : Optional.empty();
			case "getHeight":
				if (count == 0) return this.view.levelMaxY - this.view.levelMinY;
				if (count == 3) return this.view.height(((Heightmap.Types)(args[0])).isOpaque(), (Integer)(args[1]), (Integer)(args[2]));
				break;
			case "getHeightmapPos":
				if (count == 2) {
					BlockPos pos = (BlockPos)(args[1]);
					return new BlockPos(pos.getX(), this.view.height(((Heightmap.Types)(args[0])).isOpaque(), pos.getX(), pos.getZ()), pos.getZ());
				}
				break;
			case "getMinY":
				if (count == 0) return this.view.levelMinY;
				break;
			case "ensureCanWrite":
				return true;
			// --------------------------------------------------------------------------------------------- chunks
			case "getChunk":
				if (count >= 2 && args[0] instanceof Integer chunkX && args[1] instanceof Integer chunkZ) return this.chunk(chunkX, chunkZ);
				if (count == 1 && args[0] instanceof BlockPos pos) return this.chunk(pos.getX() >> 4, pos.getZ() >> 4);
				break;
			case "getChunkForCollisions":
				if (count == 2) return this.chunk((Integer)(args[0]), (Integer)(args[1]));
				break;
			case "hasChunk":
			case "hasChunkAt":
			case "hasChunksAt":
			case "isLoaded":
				return true;
			// --------------------------------------------------------------------------------------------- biomes
			case "getBiome":
				if (count == 1 && args[0] instanceof BlockPos pos) return this.biome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
				break;
			case "getNoiseBiome":
			case "getUncachedNoiseBiome":
				if (count == 3) return this.biome((Integer)(args[0]), (Integer)(args[1]), (Integer)(args[2]));
				break;
			// --------------------------------------------------------------------------------------------- the level
			case "getSeed":
				return this.owner.level.getSeed();
			case "getLevel":
				return this.owner.level;
			case "getServer":
				return this.owner.level.getServer();
			case "registryAccess":
				return this.owner.level.registryAccess();
			case "enabledFeatures":
				return this.owner.levelAccess.enabledFeatures();
			case "dimensionType":
				return this.owner.level.dimensionType();
			case "getSeaLevel":
				return this.owner.levelAccess.getSeaLevel();
			case "getLevelData":
				return this.owner.level.getLevelData();
			case "getWorldBorder":
				return this.owner.levelAccess.getWorldBorder();
			case "environmentAttributes":
				return this.owner.levelAccess.environmentAttributes();
			case "getChunkSource":
				// pieces only read the generator from it
				return this.owner.level.getChunkSource();
			case "getCurrentDifficultyAt":
				return this.owner.difficulty;
			case "getRandom":
				return this.random;
			case "isClientSide":
				return false;
			case "nextSubTickCount":
				return 0L;
			case "getSkyDarken":
				return 0;
			// --------------------------------------------------------------------------------------------- light
			case "getLightEngine":
				return null;
			case "getBrightness":
			case "getRawBrightness":
			case "getMaxLocalRawBrightness":
				return 15;
			case "canSeeSky":
				return true;
			// --------------------------------------------------------------------------------------------- dropped
			case "scheduleTick":
			case "blockUpdated":
			case "updateNeighborsAt":
			case "updateNeighborsAtExceptFromFacing":
			case "neighborShapeChanged":
			case "updateNeighbourForOutputSignal":
			case "gameEvent":
			case "playSound":
			case "levelEvent":
			case "addParticle":
			case "addAlwaysVisibleParticle":
			case "broadcastEntityEvent":
			case "addFreshEntityWithPassengers":
			case "setCurrentlyGenerating":
				return null;
			case "addFreshEntity":
				return true;
			case "getEntities":
			case "getEntitiesOfClass":
			case "players":
			case "getEntityCollisions":
				return List.of();
			// --------------------------------------------------------------------------------------------- Object
			case "toString":
				if (count == 0) return "SeedGenGlobe structure capture";
				break;
			case "hashCode":
				if (count == 0) return System.identityHashCode(proxy);
				break;
			case "equals":
				if (count == 1) return proxy == args[0];
				break;
			default:
				break;
		}
		if (method.isDefault()) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		if (REPORTED.add(method.getName())) {
			SeedGenGlobe.LOGGER.debug("SeedGenGlobe: a structure piece asked its world for {}, which LOD generation does not provide", method);
		}
		return nothing(method.getReturnType());
	}

	static @Nullable Object nothing(Class<?> type) {
		if (type == void.class) return null;
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0.0F;
		if (type == double.class) return 0.0D;
		if (type == byte.class) return (byte)(0);
		if (type == short.class) return (short)(0);
		if (type == char.class) return '\0';
		if (type == Optional.class) return Optional.empty();
		if (type == List.class || type == Iterable.class || type == java.util.Collection.class) return List.of();
		if (type == Set.class) return Set.of();
		if (type == Map.class) return Collections.emptyMap();
		if (type == Stream.class) return Stream.empty();
		return null;
	}
}
