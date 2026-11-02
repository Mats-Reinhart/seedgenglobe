package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.GlobeMixinPlugin;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;

/**
 * {@code /seedgenglobeclient}: what SeedGenGlobe does on this client. Useful in multiplayer, where the server may not
 * have SeedGenGlobe (or allow its command) and LODs are generated locally.
 */
final class ClientStatusCommand {

	static final String NAME = "seedgenglobeclient";

	private ClientStatusCommand() {}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(
			LiteralArgumentBuilder.<FabricClientCommandSource>literal(NAME).executes(context -> {
				FabricClientCommandSource source = context.getSource();
				for (String line : lines(source.getClient())) {
					source.sendFeedback(Component.literal(line));
				}
				return 1;
			})
		);
	}

	static List<String> lines(Minecraft minecraft) {
		GlobeConfig config = GlobeConfig.get();
		List<String> lines = new ArrayList<>();
		lines.add("SeedGenGlobe " + ModCompat.version(SeedGenGlobe.MOD_ID) + " (client)" + (config.enabled ? "" : " (disabled)") + ": " + config.summary());
		if (minecraft.hasSingleplayerServer()) {
			lines.add("Singleplayer: the integrated server generates LODs, see /seedgenglobe for its numbers.");
		}
		List<String> generation = ClientGeneration.statusLines();
		if (generation.isEmpty() && !minecraft.hasSingleplayerServer()) {
			lines.add(config.clientGeneration
				? "No Big Globe level is generated on this client right now."
				: "Client generation is off (clientGeneration=false).");
		}
		lines.addAll(generation);
		if (VertigoGuard.enabled()) lines.add(VertigoGuard.status());
		String colours;
		if (!config.bigGlobeColors) colours = "off (bigGlobeColors=false)";
		else if (GlobeMixinPlugin.SKIPPED.containsKey("ClientBlockStateColorCacheMixin")) colours = "unavailable with this Distant Horizons build";
		else if (!GlobeColors.enabled()) colours = "off after an error (see the log)";
		else colours = "on";
		lines.add("Big Globe colours for LODs: " + colours);
		lines.addAll(SeedGenGlobeClient.xaeroStatus());
		lines.addAll(GlobeMixinPlugin.problems());
		return lines;
	}
}
