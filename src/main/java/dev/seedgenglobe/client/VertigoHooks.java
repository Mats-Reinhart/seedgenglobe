package dev.seedgenglobe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import builderb0y.vertigo.api.VertigoAPI;
import builderb0y.vertigo.api.VertigoClientEvents;

import dev.seedgenglobe.SeedGenGlobe;

/** The only class that touches Vertigo's API; loaded only when Vertigo is installed. */
final class VertigoHooks {

	private VertigoHooks() {}

	static void register() {
		VertigoClientEvents.SECTION_LOADED.register(VertigoGuard::onSectionLoaded);
		VertigoClientEvents.SECTION_UNLOADED.register(VertigoGuard::onSectionUnloaded);
		SeedGenGlobe.LOGGER.info("SeedGenGlobe: Vertigo detected, chunks cut off by vertical syncing will not overwrite Distant Horizons LODs.");
	}

	/** Render thread only, as Vertigo's API requires. */
	static boolean sectionLoaded(int sectionX, int sectionY, int sectionZ) {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null || VertigoAPI.isSectionLoaded(player, sectionX, sectionY, sectionZ);
	}

	/** Render thread only, as Vertigo's API requires. */
	static boolean trackingSections(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		return player != null && VertigoAPI.isTrackingSections(player);
	}
}
