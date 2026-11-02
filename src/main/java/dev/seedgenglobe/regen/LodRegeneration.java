package dev.seedgenglobe.regen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.LodMapFolders;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;

/**
 * Regenerating a world's LODs ({@code /seedgenglobe regenerate}, or the button on the General page of the config
 * screen): deletes Distant Horizons' LOD database of every dimension of the world ({@code DistantHorizons.sqlite} with
 * its {@code -wal}, {@code -shm} and {@code -journal} files) and, in singleplayer, SeedGenGlobe's copy of the Xaero
 * LOD map of the dimensions it generates. When the world is opened again, Distant Horizons finds no LODs and asks for
 * all of them, and the Xaero LOD map is drawn again from the new ones. The world itself, Xaero's own map of explored
 * chunks and waypoints are never touched.
 * <p>
 * Distant Horizons keeps each database open while the world runs, and deleting an open SQLite database is not clean:
 * Windows refuses, and elsewhere the open connection would go on writing a journal that ends up next to the new,
 * empty database. So confirming only <em>schedules</em> the deletion. A marker file in the world folder
 * ({@code seedgenglobe/regenerate-lods.txt}) lists what to delete, and the files go when the world closes, after
 * Distant Horizons closed its connections ({@code SERVER_STOPPED}, ordered after Distant Horizons' own listener, and
 * only once every connection it had open on those files reports closed). Whatever could not be deleted then (still
 * open, or locked by another program) is deleted when the world loads next, before Distant Horizons opens anything
 * ({@code SERVER_STARTING}). Deleting the marker file cancels.
 * <p>
 * Whatever the marker file says, only files named {@code DistantHorizons.sqlite[-wal|-shm|-journal]} inside the world
 * folder and map folders under {@code <game>/seedgenglobe/lodmap} are ever deleted.
 */
public final class LodRegeneration {

	static final String MARKER_FOLDER = "seedgenglobe";
	static final String MARKER_NAME = "regenerate-lods.txt";
	static final String DATABASE = "DistantHorizons.sqlite";
	/** The database first: while it cannot be deleted, its journal stays with it. */
	static final List<String> DATABASE_FILES = List.of("", "-wal", "-shm", "-journal");
	/** How long the first {@code /seedgenglobe regenerate} waits for the second. */
	public static final long CONFIRM_MILLIS = 30_000L;

	private static final Pattern TERRAIN = Pattern.compile("[0-9a-f]{1,16}");
	private static final Pattern MAP_FOLDER = Pattern.compile("[a-z0-9_.\\-]{1,200}");

	/** Who sent {@code /seedgenglobe regenerate} once, and when: the second send within {@link #CONFIRM_MILLIS} confirms. */
	private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();
	/** The connections Distant Horizons had open on the scheduled databases, by file: closed before anything is deleted. */
	private static final Map<Path, Connection> CONNECTIONS = new ConcurrentHashMap<>();

	/**
	 * Lets the Xaero LOD map drop its folders before they are deleted (set by the client when Xaero's World Map is
	 * installed): forgets what it keeps for them and runs {@code delete} once it is done writing there. Without it the
	 * folders are deleted directly.
	 */
	public interface MapCleaner {
		void discard(List<Path> folders, Runnable delete);
	}

	private static volatile @Nullable MapCleaner mapCleaner;

	private LodRegeneration() {}

	public static void setMapCleaner(@Nullable MapCleaner cleaner) {
		mapCleaner = cleaner;
	}

	/**
	 * What regenerating deletes: the databases (relative to the world folder), the dimensions they belong to, the Xaero
	 * LOD map entries ({@code <terrain>/<dimension>}) and the databases' size.
	 */
	public record Plan(List<String> databases, List<String> dimensions, List<String> lodMaps, boolean xaeroMap, long bytes) {

		boolean isEmpty() {
			return this.databases.isEmpty();
		}

		/** "3 dimensions (overworld, the_nether, the_end), 1.2 GB" */
		public String describe() {
			StringBuilder text = new StringBuilder();
			text.append(this.dimensions.size()).append(this.dimensions.size() == 1 ? " dimension" : " dimensions");
			if (!this.dimensions.isEmpty()) text.append(" (").append(String.join(", ", this.dimensions)).append(')');
			if (this.bytes > 0L) text.append(", ").append(size(this.bytes));
			return text.toString();
		}
	}

	/** The outcome of asking, confirming or cancelling, with the message for whoever asked. */
	public record Reply(boolean ok, String message) {}

	// ------------------------------------------------------------------------------------------------ asking

	/**
	 * {@code /seedgenglobe regenerate}: the first send says what would be deleted and asks to send the command again;
	 * a second send within {@link #CONFIRM_MILLIS} schedules it. Server thread.
	 */
	public static Reply command(MinecraftServer server, String who) {
		if (!GlobeConfig.get().enabled) return switchedOff();
		Path world = world(server);
		if (Files.isRegularFile(marker(world))) {
			ASKED.remove(who);
			return new Reply(true, "LOD regeneration is already scheduled for this world. " + whenText() + " " + undoText());
		}
		long now = System.currentTimeMillis();
		Long asked = ASKED.remove(who);
		if (asked != null && now - asked <= CONFIRM_MILLIS) return schedule(server, who);
		Plan plan;
		try {
			plan = plan(server);
		}
		catch (LinkageError error) {
			return unsupported(error);
		}
		if (plan.isEmpty()) return nothing();
		ASKED.put(who, now);
		return new Reply(true, "This deletes the LODs of this world: Distant Horizons' LOD database of " + plan.describe()
			+ (plan.xaeroMap() ? ", and SeedGenGlobe's Xaero LOD map of it" : "")
			+ ". The world, Xaero's map of explored chunks and waypoints stay. "
			+ "Send /seedgenglobe regenerate again within " + CONFIRM_MILLIS / 1000L + " seconds to confirm.");
	}

	/**
	 * Confirmed (the second command, or the config screen's prompt): writes the marker file; the files are deleted
	 * when the world closes. Server thread.
	 */
	public static Reply schedule(MinecraftServer server, String who) {
		if (!GlobeConfig.get().enabled) return switchedOff();
		Path world = world(server);
		Plan plan;
		try {
			plan = plan(server);
		}
		catch (LinkageError error) {
			return unsupported(error);
		}
		if (plan.isEmpty()) return nothing();
		List<String> lines = new ArrayList<>();
		lines.add("# SeedGenGlobe: LODs to delete when this world closes (or when it loads next, if they were still open then).");
		lines.add("# Delete this file, or send /seedgenglobe regenerate cancel, to keep them.");
		lines.add("requested=" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + " by " + who.replaceAll("[\\r\\n]", " "));
		for (String database : plan.databases()) lines.add("dh=" + database);
		for (String map : plan.lodMaps()) lines.add("lodmap=" + map);
		Path marker = marker(world);
		try {
			Files.createDirectories(marker.getParent());
			Path temporary = marker.resolveSibling(MARKER_NAME + ".tmp");
			Files.write(temporary, lines, StandardCharsets.UTF_8);
			Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException exception) {
			SeedGenGlobe.LOGGER.error("SeedGenGlobe: could not schedule LOD regeneration, {} not written", marker, exception);
			return new Reply(false, "Could not schedule LOD regeneration: " + marker + " could not be written (" + exception.getMessage() + ").");
		}
		SeedGenGlobe.LOGGER.info("SeedGenGlobe: LOD regeneration scheduled by {}: {}{}; deleted when the world closes.",
			who, plan.describe(), plan.lodMaps().isEmpty() ? "" : ", Xaero LOD map " + String.join(", ", plan.lodMaps()));
		return new Reply(true, "LOD regeneration scheduled: " + plan.describe() + ". " + whenText()
			+ " Distant Horizons then generates all LODs again. " + undoText());
	}

	/** {@code /seedgenglobe regenerate cancel}, and the config screen's undo. Works with SeedGenGlobe switched off too. Server thread. */
	public static Reply cancel(MinecraftServer server, String who) {
		ASKED.remove(who);
		Path marker = marker(world(server));
		try {
			if (!Files.deleteIfExists(marker)) return new Reply(true, "No LOD regeneration is scheduled for this world.");
		}
		catch (IOException exception) {
			return new Reply(false, "Could not cancel: " + marker + " could not be deleted (" + exception.getMessage()
				+ "). Delete it by hand before leaving the world to keep the LODs.");
		}
		CONNECTIONS.clear();
		SeedGenGlobe.LOGGER.info("SeedGenGlobe: LOD regeneration cancelled by {}.", who);
		return new Reply(true, "LOD regeneration cancelled: the LODs of this world are kept.");
	}

	/** Whether a regeneration is scheduled for the running world (a file check, any thread). */
	public static boolean scheduled(MinecraftServer server) {
		return Files.isRegularFile(marker(world(server)));
	}

	private static Reply switchedOff() {
		return new Reply(false, "SeedGenGlobe is switched off (the Enabled button in its settings, or enabled in "
			+ GlobeConfig.FILE_NAME + "), so it does not regenerate LODs.");
	}

	private static Reply nothing() {
		return new Reply(false, "This world has no LODs to regenerate: Distant Horizons has no LOD database open in its folder.");
	}

	private static Reply unsupported(LinkageError error) {
		SeedGenGlobe.LOGGER.error("SeedGenGlobe: cannot find Distant Horizons' LOD databases in this Distant Horizons version.", error);
		return new Reply(false, "Cannot regenerate LODs: this Distant Horizons version keeps its databases differently than SeedGenGlobe expects.");
	}

	private static String whenText() {
		return ModCompat.CLIENT
			? "The LODs are deleted when you leave the world: Save and Quit to Title, then open the world again."
			: "The LODs are deleted when the server stops; start it again to regenerate them.";
	}

	private static String undoText() {
		return ModCompat.CLIENT
			? "To keep the LODs: /seedgenglobe regenerate cancel, or Keep the LODs on the General page of SeedGenGlobe's settings."
			: "To keep the LODs: /seedgenglobe regenerate cancel.";
	}

	/** What regenerating would delete now, and the connections to wait for. Server thread. */
	static Plan plan(MinecraftServer server) {
		Path world = world(server);
		List<String> databases = new ArrayList<>();
		List<String> dimensions = new ArrayList<>();
		long bytes = 0L;
		Map<Path, Connection> connections = new ConcurrentHashMap<>();
		Set<String> maps = new LinkedHashSet<>();
		if (ModCompat.DISTANT_HORIZONS) {
			for (DhDatabases.Database database : DhDatabases.open()) {
				Path file = database.file();
				if (!file.getFileName().toString().equals(DATABASE) || !inside(world, file)) {
					SeedGenGlobe.LOGGER.warn("SeedGenGlobe: {} is not in this world's folder; LOD regeneration leaves it alone.", file);
					continue;
				}
				databases.add(relative(world, file));
				dimensions.add(shortName(database.dimension()));
				for (String suffix : DATABASE_FILES) bytes += size(file.resolveSibling(DATABASE + suffix));
				if (database.connection() != null) connections.put(file, database.connection());
			}
			// the Xaero LOD map is kept by the client, so only a singleplayer world (client and server in one game) has one here
			if (ModCompat.CLIENT) {
				for (DhDatabases.Terrain terrain : DhDatabases.terrains()) {
					maps.add(LodMapFolders.terrain(terrain.columnSeed()) + "/" + LodMapFolders.dimension(terrain.dimensionId()));
				}
			}
		}
		CONNECTIONS.clear();
		CONNECTIONS.putAll(connections);
		boolean xaeroMap = false;
		for (String entry : maps) {
			if (ModCompat.XAERO_WORLD_MAP || !mapFolders(LodMapFolders.root(), entry).isEmpty()) xaeroMap = true;
		}
		return new Plan(databases, dimensions, new ArrayList<>(maps), xaeroMap, bytes);
	}

	// ------------------------------------------------------------------------------------------------ deleting

	/** {@code SERVER_STOPPED}, ordered after Distant Horizons closed its world. Registered whether or not SeedGenGlobe is enabled. */
	public static void onServerStopped(MinecraftServer server) {
		ASKED.clear();
		run(server, "the world closed");
	}

	/** {@code SERVER_STARTING}, before any level (and so any Distant Horizons database) loads. */
	public static void onServerStarting(MinecraftServer server) {
		ASKED.clear();
		run(server, "the world loaded");
	}

	private static void run(MinecraftServer server, String when) {
		Path world;
		try {
			world = world(server);
		}
		catch (RuntimeException exception) {
			return;
		}
		if (!Files.isRegularFile(marker(world))) return;
		Result result = execute(world, LodMapFolders.root(), LodRegeneration::stillOpen);
		if (!result.mapFolders().isEmpty()) discardMaps(result.mapFolders());
		if (result.failed()) return;
		if (result.deferred() == 0) {
			CONNECTIONS.clear();
			SeedGenGlobe.LOGGER.info("SeedGenGlobe: LOD regeneration done as {}: {} Distant Horizons database files deleted{}.",
				when, result.deleted(), result.mapFolders().isEmpty() ? "" : ", " + result.mapFolders().size() + " Xaero LOD map folders cleared");
		}
		else {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration as {}: {} Distant Horizons database files deleted, {} databases left for when the world loads next.",
				when, result.deleted(), result.deferred());
		}
	}

	/**
	 * What one run did: database files deleted, databases left for the next run, the map folders the marker named, and
	 * whether the marker could not be read at all.
	 */
	record Result(int deleted, int deferred, List<Path> mapFolders, boolean failed) {}

	/**
	 * Carries out the marker file of a world: deletes each listed database (and its side files) that {@code stillOpen}
	 * has no reason against, collects the map folders it names, and deletes the marker, or rewrites it with what is
	 * left. Map folders are returned, not deleted.
	 *
	 * @param stillOpen why a database cannot be deleted cleanly right now (returns null when it can)
	 */
	static Result execute(Path world, Path mapRoot, Function<Path, String> stillOpen) {
		world = world.toAbsolutePath().normalize();
		Path marker = marker(world);
		List<String> lines;
		try {
			lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			SeedGenGlobe.LOGGER.error("SeedGenGlobe: LOD regeneration: cannot read {}, nothing deleted.", marker, exception);
			return new Result(0, 0, List.of(), true);
		}
		List<String> keep = new ArrayList<>();
		List<Path> mapFolders = new ArrayList<>();
		int deleted = 0, deferred = 0;
		for (String line : lines) {
			String text = line.strip();
			if (text.startsWith("#") || text.startsWith("requested=")) {
				keep.add(line);
			}
			else if (text.startsWith("dh=")) {
				Path file = database(world, text.substring(3));
				if (file == null) {
					SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration ignores \"{}\": not a Distant Horizons database in this world's folder.", text);
					continue;
				}
				String why = stillOpen.apply(file);
				if (why != null) {
					SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration: {} is {}; it is deleted when the world loads next.", file, why);
					keep.add(line);
					deferred++;
					continue;
				}
				int count = deleteDatabase(file);
				if (count < 0) {
					keep.add(line);
					deferred++;
				}
				else deleted += count;
			}
			else if (text.startsWith("lodmap=")) {
				mapFolders.addAll(mapFolders(mapRoot, text.substring(7)));
			}
		}
		try {
			if (deferred == 0) Files.deleteIfExists(marker);
			else Files.write(marker, keep, StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration: could not update {}", marker, exception);
		}
		return new Result(deleted, deferred, mapFolders, false);
	}

	/** Why a database cannot be deleted cleanly right now, or null when it can. */
	private static @Nullable String stillOpen(Path file) {
		if (!ModCompat.DISTANT_HORIZONS) return null;
		try {
			Connection connection = CONNECTIONS.get(file);
			if (connection != null) {
				if (DhDatabases.isOpen(connection)) return "still open in Distant Horizons";
				CONNECTIONS.remove(file);
			}
			for (DhDatabases.Database open : DhDatabases.open()) {
				if (open.file().equals(file)) return "loaded by Distant Horizons";
			}
		}
		catch (LinkageError error) {
			return "possibly still open (this Distant Horizons version cannot be asked)";
		}
		return null;
	}

	/** Deletes a database and then its side files: how many existed, or -1 when one cannot be deleted (locked). */
	private static int deleteDatabase(Path file) {
		int count = 0;
		for (String suffix : DATABASE_FILES) {
			Path each = file.resolveSibling(DATABASE + suffix);
			try {
				if (Files.deleteIfExists(each)) count++;
			}
			catch (IOException exception) {
				SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration: cannot delete {} ({}); trying again when the world loads next.", each, exception.toString());
				return -1;
			}
		}
		return count;
	}

	private static void discardMaps(List<Path> folders) {
		Runnable delete = () -> {
			for (Path folder : folders) deleteTree(folder);
		};
		MapCleaner cleaner = mapCleaner;
		if (cleaner == null) {
			delete.run();
			return;
		}
		try {
			cleaner.discard(folders, delete);
		}
		catch (RuntimeException | LinkageError error) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration: the Xaero LOD map could not let go of its folders; deleting them directly", error);
			delete.run();
		}
	}

	/** Deletes a folder with everything in it, without following links out of it. */
	static void deleteTree(Path folder) {
		if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
		try {
			Files.walkFileTree(folder, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException exception) throws IOException {
					if (exception != null) throw exception;
					Files.deleteIfExists(directory);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException exception) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: LOD regeneration: could not delete all of {} ({})", folder, exception.toString());
		}
	}

	// ------------------------------------------------------------------------------------------------ paths

	static Path world(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
	}

	static Path marker(Path world) {
		return world.resolve(MARKER_FOLDER).resolve(MARKER_NAME);
	}

	/** A {@code dh=} entry of the marker file as a path, or null unless it names a Distant Horizons database inside the world folder. */
	static @Nullable Path database(Path world, String entry) {
		Path file;
		try {
			file = world.resolve(entry.strip()).toAbsolutePath().normalize();
		}
		catch (RuntimeException invalid) {
			return null;
		}
		return file.getFileName() != null && file.getFileName().toString().equals(DATABASE) && inside(world, file) ? file : null;
	}

	/**
	 * The map folders of a {@code lodmap=} entry {@code <terrain>/<dimension>}: the dimension's folder and its variants
	 * (its name followed by a dash). Empty for an entry that is not of that form.
	 */
	static List<Path> mapFolders(Path mapRoot, String entry) {
		List<Path> folders = new ArrayList<>();
		int slash = entry.indexOf('/');
		if (slash <= 0) return folders;
		String terrain = entry.substring(0, slash), prefix = entry.substring(slash + 1).strip();
		if (!TERRAIN.matcher(terrain).matches() || !MAP_FOLDER.matcher(prefix).matches() || prefix.startsWith(".")) return folders;
		Path parent = mapRoot.resolve(terrain);
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return folders;
		try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
			for (Path child : children) {
				String name = child.getFileName().toString();
				if ((name.equals(prefix) || name.startsWith(prefix + "-")) && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) folders.add(child);
			}
		}
		catch (IOException exception) {
			SeedGenGlobe.LOGGER.debug("SeedGenGlobe: could not list {}", parent, exception);
		}
		folders.sort(null);
		return folders;
	}

	static boolean inside(Path world, Path file) {
		Path normal = file.toAbsolutePath().normalize();
		return normal.startsWith(world) && !normal.equals(world);
	}

	static String relative(Path world, Path file) {
		return world.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
	}

	/** {@code minecraft:the_nether} → {@code the_nether}; other names stay as they are. */
	static String shortName(String dimension) {
		return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
	}

	private static long size(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.size(file) : 0L;
		}
		catch (IOException exception) {
			return 0L;
		}
	}

	static String size(long bytes) {
		if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
		if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
		return String.format(Locale.ROOT, "%.0f KB", Math.max(1.0D, bytes / 1024.0D));
	}
}
