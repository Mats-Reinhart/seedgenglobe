package dev.seedgenglobe.regen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks what LOD regeneration deletes when it carries out a world's marker file ({@link LodRegeneration#execute}),
 * on a made-up world folder and LOD map folder: only Distant Horizons databases (and their side files) inside the
 * world, only the named dimensions' map folders, nothing for malformed or escaping entries, databases that are still
 * open left for the next run with the marker rewritten, and no links followed out of a map folder.
 */
public final class LodRegenerationTest {

	static int checks;

	static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) throws IOException {
		Path root = Files.createTempDirectory("seedgenglobe-regen-test");
		try {
			run(root);
		}
		finally {
			try (Stream<Path> files = Files.walk(root)) {
				for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
		System.out.println("LodRegenerationTest: " + checks + " checks passed");
	}

	static void run(Path root) throws IOException {
		Path world = root.resolve("saves").resolve("New World");
		Path mapRoot = root.resolve("seedgenglobe").resolve("lodmap");
		Path outside = root.resolve("outside");

		// the world: two dimensions' databases with side files, and files that must stay
		Path overworld = file(world.resolve("data/DistantHorizons.sqlite"));
		Path overworldWal = file(world.resolve("data/DistantHorizons.sqlite-wal"));
		Path overworldShm = file(world.resolve("data/DistantHorizons.sqlite-shm"));
		Path nether = file(world.resolve("DIM-1/data/DistantHorizons.sqlite"));
		Path netherJournal = file(world.resolve("DIM-1/data/DistantHorizons.sqlite-journal"));
		List<Path> stay = List.of(
			file(world.resolve("level.dat")),
			file(world.resolve("data/raids.dat")),
			file(world.resolve("data/DistantHorizons.sqlite.bak")),
			file(world.resolve("region/r.0.0.mca")),
			file(outside.resolve("DistantHorizons.sqlite")),
			file(root.resolve("saves/Other World/data/DistantHorizons.sqlite"))
		);

		// the map: the overworld with two variants, a dimension whose name only starts the same, the nether, another terrain
		Path mapOverworld = file(mapRoot.resolve("abc123/minecraft_overworld/r.0.0.4.1.0.bin")).getParent();
		Path mapOverworldVariant = file(mapRoot.resolve("abc123/minecraft_overworld-noflowers-s5/r.1.0.4.1.0.bin")).getParent();
		file(mapOverworldVariant.resolve("index-1234.bin"));
		Path mapLookalike = file(mapRoot.resolve("abc123/minecraft_overworld_caves/r.0.0.4.1.0.bin")).getParent();
		Path mapNether = file(mapRoot.resolve("abc123/minecraft_the_nether-nostructures/r.0.0.4.1.0.bin")).getParent();
		Path mapOtherTerrain = file(mapRoot.resolve("def456/minecraft_overworld/r.0.0.4.1.0.bin")).getParent();
		// a link inside a map folder that points out of it: deleting the folder must not follow it
		Path linked = file(outside.resolve("keep-me/important.txt")).getParent();
		boolean links = true;
		try {
			Files.createSymbolicLink(mapOverworld.resolve("link"), linked);
		}
		catch (IOException | UnsupportedOperationException exception) {
			links = false;
		}

		Path marker = LodRegeneration.marker(world);
		Files.createDirectories(marker.getParent());
		Files.write(marker, List.of(
			"# a comment",
			"requested=2026-11-02T10:00:00Z by Tester",
			"dh=data/DistantHorizons.sqlite",
			"dh=DIM-1/data/DistantHorizons.sqlite",
			"dh=../../outside/DistantHorizons.sqlite",
			"dh=../Other World/data/DistantHorizons.sqlite",
			"dh=" + outside.resolve("DistantHorizons.sqlite").toAbsolutePath(),
			"dh=level.dat",
			"dh=data/raids.dat",
			"dh=",
			"lodmap=abc123/minecraft_overworld",
			"lodmap=abc123/minecraft_the_nether",
			"lodmap=abc123/../../outside",
			"lodmap=ABC123/minecraft_overworld",
			"lodmap=abc123/.hidden",
			"lodmap=nonsense",
			"something else"
		), StandardCharsets.UTF_8);

		// first run: the nether is still open
		List<Path> asked = new ArrayList<>();
		LodRegeneration.Result first = LodRegeneration.execute(world, mapRoot, file -> {
			asked.add(file);
			return file.equals(nether.toAbsolutePath().normalize()) ? "still open in Distant Horizons" : null;
		});
		check(!first.failed(), "the marker was read");
		check(first.deleted() == 3, "the overworld database and its two side files were deleted, not " + first.deleted());
		check(first.deferred() == 1, "the open nether database waits, not " + first.deferred());
		check(asked.size() == 2, "only the two real databases were checked, not " + asked);
		check(!Files.exists(overworld) && !Files.exists(overworldWal) && !Files.exists(overworldShm), "overworld files gone");
		check(Files.exists(nether) && Files.exists(netherJournal), "the open nether database and its journal stay together");
		for (Path each : stay) check(Files.exists(each), each + " stays");
		check(Files.isDirectory(world.resolve("data")), "the data folder itself stays");
		List<Path> folders = first.mapFolders();
		check(folders.size() == 3, "three map folders named: " + folders);
		check(folders.contains(mapOverworld) && folders.contains(mapOverworldVariant) && folders.contains(mapNether), "the named dimensions and their variants");
		check(!folders.contains(mapLookalike), "a dimension that only starts with the same name is not included");
		check(!folders.contains(mapOtherTerrain), "another terrain's map is not included");

		check(Files.isRegularFile(marker), "the marker stays while a database waits");
		List<String> left = Files.readAllLines(marker, StandardCharsets.UTF_8);
		check(left.contains("dh=DIM-1/data/DistantHorizons.sqlite"), "the waiting database is still listed");
		check(left.contains("requested=2026-11-02T10:00:00Z by Tester"), "the request line is kept");
		check(left.stream().noneMatch(line -> line.startsWith("lodmap=") || line.contains("overworld") || line.contains("outside")),
			"handled, ignored and map entries are not listed again: " + left);

		// the map folders as the server deletes them without Xaero's World Map
		for (Path folder : folders) LodRegeneration.deleteTree(folder);
		check(!Files.exists(mapOverworld) && !Files.exists(mapOverworldVariant) && !Files.exists(mapNether), "named map folders deleted");
		check(Files.exists(mapLookalike) && Files.exists(mapOtherTerrain), "other map folders stay");
		if (links) check(Files.exists(linked.resolve("important.txt")), "a link out of a map folder is not followed");

		// second run (the world loads next): nothing open any more
		LodRegeneration.Result second = LodRegeneration.execute(world, mapRoot, file -> null);
		check(second.deleted() == 2 && second.deferred() == 0, "the nether database and its journal go: " + second);
		check(!Files.exists(nether) && !Files.exists(netherJournal), "nether files gone");
		check(!Files.exists(marker), "the marker is gone once everything is deleted");
		for (Path each : stay) check(Files.exists(each), each + " still stays");

		// paths
		Path normalWorld = world.toAbsolutePath().normalize();
		check(LodRegeneration.database(normalWorld, "data/DistantHorizons.sqlite") != null, "a database in the world");
		check(LodRegeneration.database(normalWorld, "data/DistantHorizons.sqlite-wal") == null, "side files are never listed on their own");
		check(LodRegeneration.database(normalWorld, "../x/DistantHorizons.sqlite") == null, "not outside the world");
		check(LodRegeneration.database(normalWorld, "DistantHorizons.sqlite") != null, "the world folder's own top level counts as inside");
		check(!LodRegeneration.inside(normalWorld, normalWorld), "the world folder is not inside itself");
		check(LodRegeneration.relative(normalWorld, normalWorld.resolve("DIM-1").resolve("data").resolve("DistantHorizons.sqlite")).equals("DIM-1/data/DistantHorizons.sqlite"), "relative paths use slashes");
		check(LodRegeneration.shortName("minecraft:the_nether").equals("the_nether"), "short dimension names");
		check(LodRegeneration.shortName("bigglobe:overworld").equals("bigglobe:overworld"), "other namespaces stay");
		check(LodRegeneration.size(5L << 30).equals("5.0 GB") && LodRegeneration.size(300L << 20).equals("300 MB") && LodRegeneration.size(10).equals("1 KB"), "sizes");

		// a marker that cannot be read deletes nothing
		check(LodRegeneration.execute(root.resolve("no world"), mapRoot, file -> null).failed(), "no marker: nothing done");
	}

	static Path file(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, "x");
		return path;
	}
}
