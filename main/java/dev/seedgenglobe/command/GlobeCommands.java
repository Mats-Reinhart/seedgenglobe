package dev.seedgenglobe.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import org.jetbrains.annotations.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.GlobeMixinPlugin;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.terrain.ColumnProfile;

/**
 * {@code /seedgenglobe debug ...}: tools for finding out what SeedGenGlobe does, for operators. Everything a player
 * needs day to day (settings, progress, status) is in the config screen (Mod Menu) and the progress overlay.
 * <ul>
 * <li>{@code status}: what every generator, the scheduler and Distant Horizons' queues are doing;</li>
 * <li>{@code verify [radiusChunks]}: predicted surface heights against the real chunks around you;</li>
 * <li>{@code benchmark [sections] [detail]}: time sections on one background thread;</li>
 * <li>{@code probe}: the column under you as SeedGenGlobe computes it;</li>
 * <li>{@code structures [radiusChunks]}: the structures LODs get around you.</li>
 * </ul>
 * Distant Horizons classes are only touched through {@link DhAccess}, so the command also loads without DH.
 */
public final class GlobeCommands {

	private GlobeCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> debug = LiteralArgumentBuilder.literal("debug");
		debug.then(LiteralArgumentBuilder.<CommandSourceStack>literal("status").executes(GlobeCommands::status));
		debug.then(
			LiteralArgumentBuilder.<CommandSourceStack>literal("verify")
			.executes(context -> verify(context, 8))
			.then(
				RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("radiusChunks", IntegerArgumentType.integer(1, 32))
				.executes(context -> verify(context, IntegerArgumentType.getInteger(context, "radiusChunks")))
			)
		);
		debug.then(
			LiteralArgumentBuilder.<CommandSourceStack>literal("benchmark")
			.executes(context -> benchmark(context, 16, -1))
			.then(
				RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("sections", IntegerArgumentType.integer(1, 512))
				.executes(context -> benchmark(context, IntegerArgumentType.getInteger(context, "sections"), -1))
				.then(
					RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("detail", IntegerArgumentType.integer(0, 15))
					.executes(context -> benchmark(context, IntegerArgumentType.getInteger(context, "sections"), IntegerArgumentType.getInteger(context, "detail")))
				)
			)
		);
		debug.then(LiteralArgumentBuilder.<CommandSourceStack>literal("probe").executes(GlobeCommands::probe));
		debug.then(
			LiteralArgumentBuilder.<CommandSourceStack>literal("structures")
			.executes(context -> structures(context, 64))
			.then(
				RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("radiusChunks", IntegerArgumentType.integer(1, 4096))
				.executes(context -> structures(context, IntegerArgumentType.getInteger(context, "radiusChunks")))
			)
		);
		LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal("seedgenglobe").requires(GlobeCommands::isGamemaster);
		root.then(debug);
		dispatcher.register(root);
	}

	static boolean isGamemaster(CommandSourceStack source) {
		return Commands.LEVEL_GAMEMASTERS.check(source.permissions());
	}

	static void say(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal(message), false);
	}

	static void fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
	}

	static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		GlobeConfig config = GlobeConfig.get();
		say(source, "SeedGenGlobe " + ModCompat.version(SeedGenGlobe.MOD_ID) + (config.enabled ? "" : " (disabled)") + ": " + config.summary());
		say(source, "Threads: " + dev.seedgenglobe.gen.SectionScheduler.get().describe());
		if (!ModCompat.DISTANT_HORIZONS) {
			say(source, "Distant Horizons is not installed on this side.");
		}
		else {
			List<String> lines = DhAccess.statusLines();
			if (lines.isEmpty()) say(source, "No Big Globe dimension is being generated here yet.");
			for (String line : lines) say(source, line);
			for (String line : GlobeMixinPlugin.problems()) say(source, line);
		}
		for (String line : ModCompat.describe()) say(source, "- " + line);
		return 1;
	}

	/** Predicted surface vs. the real heightmap of chunks that are already loaded. */
	static int verify(CommandContext<CommandSourceStack> context, int radiusChunks) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		DhAccess.Probe probe = DhAccess.probeFor(level);
		if (probe == null) {
			fail(source, "SeedGenGlobe is not generating this dimension (is it a Big Globe dimension with Distant Horizons installed?).");
			return 0;
		}
		Vec3 position = source.getPosition();
		int centerChunkX = ((int)(Math.floor(position.x))) >> 4;
		int centerChunkZ = ((int)(Math.floor(position.z))) >> 4;
		// read real heights on the server thread, compute predictions off it
		List<int[]> samples = new ArrayList<>();
		for (int chunkZ = centerChunkZ - radiusChunks; chunkZ <= centerChunkZ + radiusChunks; chunkZ++) {
			for (int chunkX = centerChunkX - radiusChunks; chunkX <= centerChunkX + radiusChunks; chunkX++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) continue;
				for (int offset = 0; offset < 4; offset++) {
					int x = (chunkX << 4) | ((offset & 1) << 3) | 4;
					int z = (chunkZ << 4) | ((offset >> 1) << 3) | 4;
					int realTop = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
					samples.add(new int[] { x, z, realTop });
				}
			}
		}
		if (samples.isEmpty()) {
			fail(source, "No loaded chunks nearby to compare against.");
			return 0;
		}
		say(source, "SeedGenGlobe: comparing " + samples.size() + " columns against real chunks...");
		MinecraftServer server = source.getServer();
		CompletableFuture.runAsync(() -> {
			int exact = 0, close = 0;
			long absError = 0;
			int worst = 0;
			int[] worstAt = null;
			for (int[] sample : samples) {
				int predicted = probe.surfaceTop(sample[0], sample[1]);
				int error = Math.abs(predicted - sample[2]);
				if (error == 0) exact++;
				if (error <= 2) close++;
				absError += error;
				if (error > worst) {
					worst = error;
					worstAt = sample;
				}
			}
			int n = samples.size();
			String report = String.format(
				Locale.ROOT,
				"SeedGenGlobe verify: %d columns, %.1f%% exact, %.1f%% within 2 blocks, mean error %.2f blocks, worst %d%s. (Trees, structures and player builds account for most differences.)",
				n,
				100.0D * exact / n,
				100.0D * close / n,
				(double)(absError) / n,
				worst,
				worstAt != null ? " at " + worstAt[0] + ", " + worstAt[1] : ""
			);
			server.execute(() -> say(source, report));
		}).exceptionally(throwable -> {
			server.execute(() -> fail(source, "SeedGenGlobe verify failed: " + throwable));
			return null;
		});
		return 1;
	}

	static int probe(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		DhAccess.Probe probe = DhAccess.probeFor(source.getLevel());
		if (probe == null) {
			fail(source, "SeedGenGlobe is not generating this dimension.");
			return 0;
		}
		Vec3 position = source.getPosition();
		int x = (int)(Math.floor(position.x)), z = (int)(Math.floor(position.z));
		ColumnProfile profile = probe.column(x, z);
		say(source, "SeedGenGlobe column at " + x + ", " + z + ": surface top y=" + profile.surfaceTop + ", biome " + (profile.biome != null ? profile.biome.getRegisteredName() : "?"));
		StringBuilder runs = new StringBuilder();
		for (int run = 0; run < Math.min(profile.count, 8); run++) {
			if (run > 0) runs.append(" | ");
			runs.append(profile.bottoms[run]).append("..").append(profile.tops[run]).append(' ').append(profile.states[run].getBlock().getName().getString());
		}
		say(source, runs.toString());
		return 1;
	}

	/**
	 * The structures SeedGenGlobe puts into LODs around the player. Up to 128 chunks away every structure is listed,
	 * up to 512 chunks the large ones (lakes, mega trees), farther out only the giant ones (obelisks), like far LODs
	 * get them, which keeps a wide search quick.
	 */
	static int structures(CommandContext<CommandSourceStack> context, int radiusChunks) {
		CommandSourceStack source = context.getSource();
		DhAccess.Probe probe = DhAccess.probeFor(source.getLevel());
		if (probe == null) {
			fail(source, "SeedGenGlobe is not generating this dimension.");
			return 0;
		}
		if (probe.structureDetail() < 0) {
			fail(source, "SeedGenGlobe puts no structures into this dimension's LODs (turned off in its config, or for this world).");
			return 0;
		}
		Vec3 position = source.getPosition();
		int x = (int)(Math.floor(position.x)), z = (int)(Math.floor(position.z));
		int tier = radiusChunks <= 128 ? 0 : (radiusChunks <= 512 ? 1 : 2);
		MinecraftServer server = source.getServer();
		say(source, "SeedGenGlobe: looking for " + (tier == 0 ? "structures" : (tier == 1 ? "large structures" : "giant structures")) + " within " + radiusChunks + " chunks...");
		CompletableFuture.runAsync(() -> {
			List<String> lines = probe.structures(x, z, radiusChunks << 4, tier, 20);
			server.execute(() -> {
				if (lines == null || lines.isEmpty()) {
					say(source, "None found.");
					return;
				}
				for (String line : lines) say(source, "- " + line);
			});
		}).exceptionally(throwable -> {
			server.execute(() -> fail(source, "SeedGenGlobe structure search failed: " + throwable));
			return null;
		});
		return 1;
	}

	static int benchmark(CommandContext<CommandSourceStack> context, int sections, int detail) {
		CommandSourceStack source = context.getSource();
		DhAccess.Probe probe = DhAccess.probeFor(source.getLevel());
		if (probe == null) {
			fail(source, "SeedGenGlobe is not generating this dimension.");
			return 0;
		}
		Vec3 position = source.getPosition();
		int x = (int)(Math.floor(position.x)), z = (int)(Math.floor(position.z));
		MinecraftServer server = source.getServer();
		say(source, "SeedGenGlobe: benchmarking " + sections + " sections" + (detail >= 0 ? " at detail " + detail : " at detail 0, 3 and 6") + " on a background thread...");
		CompletableFuture.runAsync(() -> {
			List<String> lines = new ArrayList<>();
			int[] details = detail >= 0 ? new int[] { detail } : new int[] { 0, 3, 6 };
			for (int d : details) {
				lines.add(probe.benchmark(x, z, sections, d));
			}
			server.execute(() -> {
				for (String line : lines) say(source, line);
			});
		}).exceptionally(throwable -> {
			server.execute(() -> fail(source, "SeedGenGlobe benchmark failed: " + throwable));
			return null;
		});
		return 1;
	}

	/** Keeps command code free of Distant Horizons types. */
	public static final class DhAccess {

		public interface Probe {

			int surfaceTop(int x, int z);

			ColumnProfile column(int x, int z);

			String benchmark(int x, int z, int sections, int detail);

			/** @return null when this dimension's LODs get no structures */
			@Nullable List<String> structures(int x, int z, int radius, int tier, int limit);

			/** @return the coarsest detail level with every structure, or -1 without structures */
			int structureDetail();
		}

		static List<String> statusLines() {
			try {
				return dev.seedgenglobe.dh.DiagnosticsBridge.statusLines();
			}
			catch (LinkageError error) {
				return List.of("Distant Horizons API mismatch: " + error);
			}
		}

		static Probe probeFor(ServerLevel level) {
			if (!ModCompat.DISTANT_HORIZONS) return null;
			try {
				return dev.seedgenglobe.dh.DiagnosticsBridge.probeFor(level);
			}
			catch (LinkageError error) {
				return null;
			}
		}
	}

}
