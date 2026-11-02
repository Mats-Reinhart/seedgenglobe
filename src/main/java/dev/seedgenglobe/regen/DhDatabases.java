package dev.seedgenglobe.regen;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.world.AbstractDhServerWorld;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;

import dev.seedgenglobe.dh.DhBridge;
import dev.seedgenglobe.dh.GlobeWorldGenerator;

/**
 * Where Distant Horizons keeps the LOD database of each dimension, and whether it still has it open; and which
 * terrains SeedGenGlobe generates here (for the Xaero LOD map folders).
 * <p>
 * Reads Distant Horizons' internal classes (3.0.3-b): the server world's loaded levels, each level's full data
 * repository and the file and connection it opened ({@code <dimension data folder>/DistantHorizons.sqlite}). All three
 * of a level's repositories (full data, chunk hashes, beacon beams) share that one connection, which Distant Horizons
 * closes when the last of them closes, as it closes the level. Only loaded while Distant Horizons is installed, and
 * kept apart so a Distant Horizons version that differs fails here, as a {@link LinkageError} the caller catches.
 */
final class DhDatabases {

	/** One dimension's database as Distant Horizons opened it. */
	record Database(String dimension, Path file, @Nullable Connection connection) {}

	/** A terrain SeedGenGlobe generates on this server: Big Globe's column seed and the dimension. */
	record Terrain(long columnSeed, String dimensionId) {}

	private DhDatabases() {}

	/**
	 * The databases of every level Distant Horizons has loaded on this server (singleplayer or dedicated), with the
	 * connection each one is open on. Empty when Distant Horizons runs no server world (a multiplayer client, or a
	 * server that is not running).
	 */
	static List<Database> open() {
		List<Database> databases = new ArrayList<>();
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (!(world instanceof AbstractDhServerWorld<?> serverWorld)) return databases;
		for (IDhLevel level : serverWorld.getAllLoadedLevels()) {
			if (!(level instanceof AbstractDhServerLevel serverLevel)) continue;
			FullDataSourceV2Repo repo = serverLevel.serverside.fullDataFileHandler.repo;
			File file = repo.databaseFile;
			if (file == null) continue;
			String dimension;
			try {
				dimension = serverLevel.getServerLevelWrapper().getDimensionName();
			}
			catch (RuntimeException exception) {
				dimension = file.getParentFile() != null ? file.getParentFile().getPath() : file.getPath();
			}
			databases.add(new Database(dimension, file.toPath().toAbsolutePath().normalize(), repo.getConnection()));
		}
		return databases;
	}

	/** Whether a connection recorded by {@link #open()} is still open. Unknown counts as open. */
	static boolean isOpen(Connection connection) {
		try {
			return !connection.isClosed();
		}
		catch (SQLException | RuntimeException exception) {
			return true;
		}
	}

	/** The terrains SeedGenGlobe generates on this server right now. */
	static List<Terrain> terrains() {
		List<Terrain> terrains = new ArrayList<>();
		for (GlobeWorldGenerator generator : DhBridge.serverGenerators()) {
			terrains.add(new Terrain(generator.terrain.worldInfo.seed(), generator.dimensionId));
		}
		return terrains;
	}
}
