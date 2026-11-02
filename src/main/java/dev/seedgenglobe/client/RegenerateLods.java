package dev.seedgenglobe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.regen.LodRegeneration;

/**
 * The "Regenerate LODs..." button on the config screen's General page: Minecraft's confirm prompt, then the same as a
 * confirmed {@code /seedgenglobe regenerate} (see {@link LodRegeneration}). Once scheduled, the button undoes it.
 * Only in singleplayer, since it deletes files of the world this game runs; on a server an operator uses the command.
 */
final class RegenerateLods {

	private RegenerateLods() {}

	static Button button(ConfigScreen screen, int x, int width) {
		Minecraft minecraft = Minecraft.getInstance();
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null || !GlobeConfig.get().enabled) {
			Button button = Button.builder(Component.literal("Regenerate LODs..."), pressed -> {}).bounds(x, 0, width, ConfigScreen.BUTTON_HEIGHT).build();
			button.active = false;
			return ConfigScreen.tooltip(button, (GlobeConfig.get().enabled ? "" : "SeedGenGlobe is disabled.\n\n")
				+ "Deletes this world's Distant Horizons LODs and SeedGenGlobe's Xaero LOD map, so they are generated again."
				+ "\n\nOnly while a singleplayer world is open. On a server, an operator sends /seedgenglobe regenerate twice.");
		}
		if (LodRegeneration.scheduled(server)) {
			Button button = Button.builder(Component.literal("Keep the LODs (undo)"), pressed -> {
				pressed.active = false;
				server.execute(() -> report(minecraft, screen, LodRegeneration.cancel(server, "the config screen")));
			}).bounds(x, 0, width, ConfigScreen.BUTTON_HEIGHT).build();
			return ConfigScreen.tooltip(button, "LOD regeneration is scheduled: this world's LODs are deleted when you leave it."
				+ "\n\nPress to keep them instead.");
		}
		Button button = Button.builder(Component.literal("Regenerate LODs..."), pressed -> minecraft.setScreen(new ConfirmScreen(
			confirmed -> {
				minecraft.setScreen(screen);
				if (confirmed) server.execute(() -> report(minecraft, screen, LodRegeneration.schedule(server, "the config screen")));
			},
			Component.literal("Regenerate the LODs of this world?"),
			Component.literal("Deletes Distant Horizons' LOD database (DistantHorizons.sqlite) of every dimension of this world,"
				+ " and SeedGenGlobe's Xaero LOD map of it. The world itself, Xaero's map of explored chunks and your waypoints stay.\n\n"
				+ "The files are deleted when you leave the world (Save and Quit to Title), once Distant Horizons has closed them."
				+ " Open the world again and all LODs are generated again."),
			Component.literal("Regenerate"),
			Component.literal("Cancel")
		))).bounds(x, 0, width, ConfigScreen.BUTTON_HEIGHT).build();
		return ConfigScreen.tooltip(button, "Deletes this world's Distant Horizons LODs and SeedGenGlobe's Xaero LOD map, so they are"
			+ " generated again. Asks first. The files go when you leave the world, once Distant Horizons has closed them."
			+ "\n\nThe same as /seedgenglobe regenerate.");
	}

	/** Server thread: hands the outcome to the client thread, for the chat and the button. */
	private static void report(Minecraft minecraft, ConfigScreen screen, LodRegeneration.Reply reply) {
		minecraft.execute(() -> {
			try {
				if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.literal(reply.message()));
				if (minecraft.screen == screen) screen.refresh();
			}
			catch (RuntimeException exception) {
				SeedGenGlobe.LOGGER.debug("SeedGenGlobe: could not show the LOD regeneration message", exception);
			}
		});
	}
}
