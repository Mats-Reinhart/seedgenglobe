package dev.seedgenglobe.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.gen.Focus;

/** Client entrypoint: multiplayer generation, the Vertigo guard, Big Globe colours, the Xaero LOD map and progress. */
public final class SeedGenGlobeClient implements ClientModInitializer {

	private static boolean vertigo;
	private static boolean xaero;

	@Override
	public void onInitializeClient() {
		GlobeConfig config = GlobeConfig.get();
		if (!config.enabled || !ModCompat.DISTANT_HORIZONS || !ModCompat.BIG_GLOBE) return;
		if (VertigoGuard.enabled()) {
			try {
				VertigoHooks.register();
				ClientChunkEvents.CHUNK_LOAD.register((ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) -> VertigoGuard.onChunkLoaded(chunk));
				ClientChunkEvents.CHUNK_UNLOAD.register((ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) -> VertigoGuard.onChunkUnloaded(chunk));
				vertigo = true;
			}
			catch (LinkageError error) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: this Vertigo version has an unexpected API; the Vertigo guard is off.", error);
			}
		}
		if (ModCompat.XAERO_WORLD_MAP) {
			// set up even when switched off in the settings, so it can be switched on in game
			try {
				dev.seedgenglobe.xaero.XaeroLodMap.init();
				// LOD regeneration clears the map's folders through it, after it let go of them
				dev.seedgenglobe.regen.LodRegeneration.setMapCleaner(dev.seedgenglobe.xaero.XaeroLodMap::discard);
				xaero = true;
			}
			catch (LinkageError error) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: this version of Xaero's World Map has an unexpected API; the LOD map for it is off.", error);
			}
		}
		ClientTickEvents.END_CLIENT_TICK.register(SeedGenGlobeClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientGeneration.onDisconnect();
			VertigoGuard.active = false;
			if (xaero) dev.seedgenglobe.xaero.XaeroLodMap.onDisconnect();
		});
		ProgressOverlay.register();
		ChatProgress.register();
		SeedGenGlobe.LOGGER.info(
			"SeedGenGlobe client: multiplayer generation {}, Vertigo guard {}, Big Globe colours {}, Xaero LOD map {}.",
			config.clientGeneration ? "on" : "off",
			vertigo ? "on" : "off",
			config.bigGlobeColors ? "on" : "off",
			xaero ? (config.xaeroLodMap ? "on" : "off (can be switched on in game)") : (ModCompat.XAERO_WORLD_MAP ? "unavailable" : "not installed")
		);
	}

	private static int focusTicks;

	/** Twice a second: where the local player is, for nearest-first generation on this client and the LOD map. */
	private static void updateFocus(Minecraft minecraft) {
		if (++focusTicks % 10 != 0) return;
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) return;
		int[] xz = new int[] { minecraft.player.getBlockX(), minecraft.player.getBlockZ() };
		String dimension = level.dimension().identifier().toString();
		// in singleplayer the server reports every player; this only fills in while it does not tick (paused)
		if (minecraft.hasSingleplayerServer()) Focus.refresh(dimension, xz);
		else Focus.set(dimension, xz);
	}

	/** Whether the Xaero LOD map integration is set up (Xaero's World Map installed and its API as expected). */
	static boolean xaero() {
		return xaero;
	}

	private static void tick(Minecraft minecraft) {
		try {
			updateFocus(minecraft);
			ClientGeneration.tick(minecraft);
			if (vertigo) {
				ClientLevel level = minecraft.level;
				boolean tracking = level != null && VertigoHooks.trackingSections(minecraft);
				// Distant Horizons only reads client chunks in multiplayer; Xaero reads them everywhere
				VertigoGuard.active = tracking && !minecraft.hasSingleplayerServer();
				if (VertigoGuard.active) VertigoGuard.tick(minecraft, level);
				if (ModCompat.XAERO_WORLD_MAP || ModCompat.XAERO_MINIMAP) dev.seedgenglobe.xaero.XaeroVertigoGuard.active = tracking;
			}
			if (xaero) dev.seedgenglobe.xaero.XaeroLodMap.tick(minecraft);
		}
		catch (Throwable throwable) {
			if (throwable instanceof VirtualMachineError error && !(throwable instanceof StackOverflowError)) throw error;
			// a persistent failure would otherwise log twenty times a second
			tickErrors++;
			if (tickErrors <= 3 || tickErrors % 6000 == 0) {
				SeedGenGlobe.LOGGER.error("SeedGenGlobe client tick failed ({} times so far)", tickErrors, throwable);
			}
		}
	}

	private static long tickErrors;

	/** Status lines of the Xaero integration, for the client status command. */
	static java.util.List<String> xaeroStatus() {
		java.util.List<String> lines = new java.util.ArrayList<>();
		if (xaero) {
			try {
				lines.addAll(dev.seedgenglobe.xaero.XaeroLodMap.statusLines());
			}
			catch (LinkageError error) {
				lines.add("Xaero LOD map: unavailable (" + error + ")");
			}
		}
		else if (ModCompat.XAERO_WORLD_MAP) {
			lines.add("Xaero LOD map: unavailable with this version of Xaero's World Map (see the log)");
		}
		if (xaero && !GlobeConfig.get().xaeroLodMap) {
			lines.add("Xaero LOD map: switched off in the settings");
		}
		if (!xaero && vertigo && (ModCompat.XAERO_WORLD_MAP || ModCompat.XAERO_MINIMAP)) {
			lines.add(dev.seedgenglobe.xaero.XaeroVertigoGuard.status());
		}
		return lines;
	}
}
