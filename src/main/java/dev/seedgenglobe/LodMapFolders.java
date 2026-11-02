package dev.seedgenglobe;

import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Where SeedGenGlobe keeps its copy of the Xaero LOD map: {@code <game>/seedgenglobe/lodmap/<terrain>/<dimension>}.
 * Shared by the map itself (client) and by LOD regeneration (server side, which deletes these folders in
 * singleplayer), so both name them the same way.
 */
public final class LodMapFolders {

	/**
	 * Version of what the LOD map stores per column; part of its folder name, so a SeedGenGlobe update that changes
	 * how columns are read starts a fresh map instead of mixing old and new regions.
	 */
	public static final int MAP_VERSION = 4;

	private LodMapFolders() {}

	/** {@code <game>/seedgenglobe/lodmap}. */
	public static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve("seedgenglobe").resolve("lodmap");
	}

	/**
	 * The folder of one terrain: hashes Big Globe's column seed (the same on servers and on clients, which never learn
	 * the world seed) together with the Big Globe version, since a different version may shape terrain differently,
	 * and {@link #MAP_VERSION}.
	 */
	public static String terrain(long columnSeed) {
		String version = ModCompat.version("bigglobe") + "/" + MAP_VERSION;
		long hash = columnSeed * 0x9E3779B97F4A7C15L ^ version.hashCode() * 0xC2B2AE3D27D4EB4FL;
		hash ^= hash >>> 29;
		return Long.toHexString(hash);
	}

	/**
	 * The start of a dimension's folder names: {@code minecraft:overworld} becomes {@code minecraft_overworld}. The map
	 * adds variants after a dash ({@code -noflowers}, {@code -s5}, {@code -nostructures}).
	 */
	public static String dimension(String dimensionId) {
		return dimensionId.replace(':', '_').replace('/', '_');
	}
}
