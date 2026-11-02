package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import builderb0y.bigglobe.ClientState;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.dh.GlobeWorldGenerator;
import dev.seedgenglobe.terrain.GlobeTerrain;
import dev.seedgenglobe.terrain.GroundCover;

/**
 * Multiplayer support: generates LODs on the client from the terrain Big Globe syncs to players.
 * <p>
 * Big Globe sends each player its dimension's terrain layer, column scripts and a SHA-256 hashed column seed
 * (it does this for its own LOD renderer). That is everything needed to compute the same terrain locally, without
 * the world seed. Distant Horizons 3.0 only generates on clients that talk to a DH server, so a few small mixins
 * let its client level request sections from SeedGenGlobe instead of the network.
 */
public final class ClientGeneration {

	static final Map<ClientLevel, LevelState> STATES = new ConcurrentHashMap<>();

	private ClientGeneration() {}

	static final class LevelState {

		final IDhApiLevelWrapper wrapper;
		final ClientLevel level;
		final ResourceKey<Level> dimension;
		volatile @Nullable GlobeWorldGenerator generator;
		volatile @Nullable ClientSectionQueue queue;
		volatile boolean reportedMissingLayers;
		final long loadedAt = System.currentTimeMillis();

		LevelState(IDhApiLevelWrapper wrapper, ClientLevel level) {
			this.wrapper = wrapper;
			this.level = level;
			this.dimension = level.dimension();
		}
	}

	/** Called from Distant Horizons' level load event for client levels. */
	public static void onLevelLoad(IDhApiLevelWrapper wrapper) {
		if (!(wrapper.getWrappedMcObject() instanceof ClientLevel level)) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.hasSingleplayerServer()) return; // the integrated server generates instead
		if (!GlobeConfig.get().clientGeneration) return;
		LevelState state = new LevelState(wrapper, level);
		LevelState previous = STATES.put(level, state);
		if (previous != null) close(previous);
		tryActivate(state);
	}

	public static void onLevelUnload(IDhApiLevelWrapper wrapper) {
		if (!(wrapper.getWrappedMcObject() instanceof ClientLevel level)) return;
		LevelState state = STATES.remove(level);
		if (state != null) close(state);
	}

	/** Client tick: activates levels whose Big Globe terrain arrived after Distant Horizons loaded them. */
	public static void tick(Minecraft minecraft) {
		if (STATES.isEmpty()) return;
		for (LevelState state : STATES.values()) {
			if (state.generator == null) tryActivate(state);
		}
	}

	public static void onDisconnect() {
		for (LevelState state : STATES.values()) close(state);
		STATES.clear();
	}

	static void close(LevelState state) {
		ClientSectionQueue queue = state.queue;
		if (queue != null) queue.close();
		GlobeWorldGenerator generator = state.generator;
		if (generator != null) {
			SeedGenGlobe.LOGGER.info("SeedGenGlobe [{}] closed: {}", generator.progress.name, generator.progress.summary());
			generator.close();
		}
		state.queue = null;
		state.generator = null;
	}

	static void tryActivate(LevelState state) {
		ClientState clientState = ClientState.get(state.dimension);
		if (clientState == null || clientState.generatorParams == null) return;
		String name = state.dimension.identifier() + " (client)";
		GlobeTerrain terrain;
		try {
			terrain = GlobeTerrain.fromClient(clientState.generatorParams, name, plains(state.level));
		}
		catch (Throwable throwable) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe could not read Big Globe's synced terrain for {}", name, throwable);
			terrain = null;
		}
		if (terrain == null) {
			if (!state.reportedMissingLayers && System.currentTimeMillis() - state.loadedAt > 10_000L) {
				state.reportedMissingLayers = true;
				SeedGenGlobe.LOGGER.info(
					"SeedGenGlobe: the server did not sync Big Globe's terrain layers for {}, so this client cannot generate LODs itself. "
					+ "Big Globe only syncs them when its LOD rendering is enabled on the server (it is off by default when the server has Distant Horizons). "
					+ "Install SeedGenGlobe on the server, or set lodRendering.enabled to ON in the server's Big Globe config.",
					state.dimension.identifier()
				);
			}
			return;
		}
		// Big Globe does not sync its configured features, so clients get every decorator except flower patches.
		GroundCover.Shared ground = GlobeConfig.get().groundCover && state.dimension.equals(Level.OVERWORLD)
			? GroundCover.Shared.create(terrain, state.wrapper.hasSkyLight(), state.wrapper.hasCeiling(), terrain.worldInfo.seed(), null)
			: null;
		// no structures: placing them needs the world seed, which a client never learns
		String dimensionId = state.dimension.identifier().toString();
		GlobeWorldGenerator generator = new GlobeWorldGenerator(state.wrapper, terrain, dimensionId, true, state.wrapper.hasSkyLight(), state.wrapper.hasCeiling(), name, ground, null);
		state.queue = new ClientSectionQueue(generator);
		state.generator = generator;
		SeedGenGlobe.LOGGER.info("SeedGenGlobe now generates Distant Horizons LODs on this client for {} ({}).", name, terrain);
	}

	static @Nullable Holder<Biome> plains(ClientLevel level) {
		try {
			return level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	/** Whether this client generates LODs for the level itself (multiplayer client generation). */
	public static boolean generates(Level level) {
		if (!(level instanceof ClientLevel clientLevel)) return false;
		LevelState state = STATES.get(clientLevel);
		return state != null && state.generator != null && !state.generator.isClosed();
	}

	/** This client's own generator for a level (multiplayer client generation), or null. */
	public static @Nullable GlobeWorldGenerator generatorFor(ClientLevel level) {
		LevelState state = STATES.get(level);
		return state != null ? state.generator : null;
	}

	static @Nullable LevelState stateFor(@Nullable DhClientLevel level) {
		if (level == null) return null;
		Object mc = level.getClientLevelWrapper().getWrappedMcObject();
		return mc instanceof ClientLevel clientLevel ? STATES.get(clientLevel) : null;
	}

	/** True when SeedGenGlobe answers this level's section requests instead of the network. */
	static @Nullable ClientSectionQueue queueFor(@Nullable DhClientLevel level, @Nullable ClientNetworkState network) {
		LevelState state = stateFor(level);
		if (state == null) return null;
		ClientSectionQueue queue = state.queue;
		if (queue == null || queue.isClosed()) return null;
		if (network != null && !GlobeConfig.get().clientGenerationWithDhServer) return null;
		return queue;
	}

	static boolean distantGenerationEnabled() {
		try {
			return DhApi.Delayed.configs == null || Boolean.TRUE.equals(DhApi.Delayed.configs.worldGenerator().enableDistantWorldGeneration().getValue());
		}
		catch (RuntimeException exception) {
			return true;
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// Called from the Distant Horizons mixins (on DH threads).

	/** DhClientLevel.shouldDoWorldGen(): without a DH server, DH never generates on a client; SeedGenGlobe can. */
	public static boolean shouldGenerate(DhClientLevel level, @Nullable ClientNetworkState network) {
		if (network != null) return false; // keep DH's own answer when a DH server is connected
		return queueFor(level, null) != null && distantGenerationEnabled();
	}

	public static @Nullable CompletableFuture<DataSourceRetrievalResult> submit(DhClientLevel level, @Nullable ClientNetworkState network, long sectionPos, byte requiredDetail) {
		ClientSectionQueue queue = queueFor(level, network);
		if (queue == null) return null;
		return queue.submit(sectionPos);
	}

	/** @return true if the call was handled and DH's own (network) handling must be skipped. */
	public static boolean setTarget(DhClientLevel level, @Nullable ClientNetworkState network, DhBlockPos2D target) {
		ClientSectionQueue queue = queueFor(level, network);
		if (queue == null) return false;
		queue.setTarget(target.x, target.z);
		// with a DH server connected the network queue still has its own work (real-time updates) to tick
		return network == null;
	}

	public static int waitingCount(DhClientLevel level, @Nullable ClientNetworkState network) {
		ClientSectionQueue queue = queueFor(level, network);
		return queue == null ? -1 : queue.waitingCount();
	}

	public static int runningCount(DhClientLevel level, @Nullable ClientNetworkState network) {
		ClientSectionQueue queue = queueFor(level, network);
		return queue == null ? -1 : queue.runningCount();
	}

	public static void removeIf(DhClientLevel level, @Nullable ClientNetworkState network, DhSectionPos.ICancelablePrimitiveLongConsumer predicate) {
		ClientSectionQueue queue = queueFor(level, network);
		if (queue != null) queue.removeIf(predicate);
	}

	public static void closing(DhClientLevel level) {
		LevelState state = stateFor(level);
		if (state != null && state.queue != null) state.queue.cancelAll();
	}

	public static int rateLimit(DhClientLevel level, @Nullable ClientNetworkState network) {
		ClientSectionQueue queue = queueFor(level, network);
		return queue == null ? -1 : queue.maxRunning();
	}

	public static List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		boolean generating = false;
		for (LevelState state : STATES.values()) {
			GlobeWorldGenerator generator = state.generator;
			if (generator != null) {
				generating = true;
				ClientSectionQueue queue = state.queue;
				lines.add(generator.progress.name + ": " + generator.progress.summary() + (queue != null ? ", Distant Horizons' requests: " + queue.waitingCount() + " waiting / " + queue.runningCount() + " running" : ""));
			}
			else {
				lines.add(state.dimension.identifier() + " (client): waiting for Big Globe to sync terrain layers from the server");
			}
		}
		if (generating) {
			lines.add("Sections run on " + dev.seedgenglobe.gen.SectionScheduler.get().describe() + ".");
		}
		return lines;
	}
}
