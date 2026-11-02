package dev.seedgenglobe.xaero;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import org.jetbrains.annotations.Nullable;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * SeedGenGlobe's own copy of the LOD surface map, one compressed file per Xaero region and detail level:
 * {@code r.<regionX>.<regionZ>.<detail>.bin}. It is kept apart from Xaero's files on purpose: Xaero rebuilds
 * singleplayer maps from the world save and only stores what it saw itself, so the LOD map is re-applied from here
 * whenever Xaero (re)loads a region, without recomputing any terrain.
 * <p>
 * Thread safe. Disk access happens on the builder thread only.
 */
public final class LodMapStore {

	final Path directory;
	/** Detail level stored on disk per region key. */
	private final ConcurrentHashMap<Long, Integer> details = new ConcurrentHashMap<>();
	private final LinkedHashMap<Long, LodRegion> cache = new LinkedHashMap<>(32, 0.75F, true);
	private final int cacheLimit;

	private LodMapStore(Path directory, int cacheLimit) {
		this.directory = directory;
		this.cacheLimit = cacheLimit;
	}

	/** Opens (and creates) a store, reading which regions it holds from the file names. */
	static LodMapStore open(Path directory, int cacheLimit) throws IOException {
		Files.createDirectories(directory);
		LodMapStore store = new LodMapStore(directory, cacheLimit);
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "r.*.bin")) {
			for (Path file : files) {
				String[] parts = file.getFileName().toString().split("\\.");
				// r . x . z . detail . bin
				if (parts.length != 5) continue;
				try {
					int x = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]), detail = Integer.parseInt(parts[3]);
					long key = LodRegion.key(x, z);
					Integer previous = store.details.get(key);
					if (previous == null || detail < previous) store.details.put(key, detail);
				}
				catch (NumberFormatException ignored) {}
			}
		}
		return store;
	}

	public int regionCount() {
		return this.details.size();
	}

	/** @return the detail level stored for the region, or -1 when there is none */
	public int detail(int regionX, int regionZ) {
		Integer detail = this.details.get(LodRegion.key(regionX, regionZ));
		return detail == null ? -1 : detail;
	}

	public @Nullable LodRegion cached(int regionX, int regionZ) {
		synchronized (this.cache) {
			return this.cache.get(LodRegion.key(regionX, regionZ));
		}
	}

	private void remember(LodRegion region) {
		synchronized (this.cache) {
			this.cache.put(LodRegion.key(region.regionX, region.regionZ), region);
			while (this.cache.size() > this.cacheLimit) {
				Long eldest = this.cache.keySet().iterator().next();
				this.cache.remove(eldest);
			}
		}
	}

	private Path file(int regionX, int regionZ, int detail) {
		return this.directory.resolve("r." + regionX + "." + regionZ + "." + detail + ".bin");
	}

	/** Builder thread: reads a region into the cache. @return null if the store has none (or it is unreadable) */
	@Nullable LodRegion load(int regionX, int regionZ) {
		LodRegion cached = this.cached(regionX, regionZ);
		if (cached != null) return cached;
		int detail = this.detail(regionX, regionZ);
		if (detail < 0) return null;
		Path file = this.file(regionX, regionZ, detail);
		try (InputStream raw = Files.newInputStream(file);
			DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw), 1 << 16))) {
			LodRegion region = LodRegion.read(in, regionX, regionZ);
			if (region == null) {
				this.forget(regionX, regionZ, detail);
				return null;
			}
			this.remember(region);
			return region;
		}
		catch (IOException | RuntimeException exception) {
			SeedGenGlobe.LOGGER.debug("SeedGenGlobe: could not read LOD map region {}", file, exception);
			this.forget(regionX, regionZ, detail);
			return null;
		}
	}

	private void forget(int regionX, int regionZ, int detail) {
		this.details.remove(LodRegion.key(regionX, regionZ), detail);
		try {
			Files.deleteIfExists(this.file(regionX, regionZ, detail));
		}
		catch (IOException ignored) {}
	}

	/** Builder thread: stores a region, replacing any coarser version. */
	void save(LodRegion region) {
		this.remember(region);
		Path file = this.file(region.regionX, region.regionZ, region.detail);
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (OutputStream raw = Files.newOutputStream(temporary);
				DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(raw), 1 << 16))) {
				region.write(out);
			}
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			long key = LodRegion.key(region.regionX, region.regionZ);
			Integer previous = this.details.put(key, region.detail);
			if (previous != null && previous != region.detail) {
				Files.deleteIfExists(this.file(region.regionX, region.regionZ, previous));
			}
		}
		catch (IOException | RuntimeException exception) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: could not save LOD map region {}: {}", file, exception.toString());
			try {
				Files.deleteIfExists(temporary);
			}
			catch (IOException ignored) {}
		}
	}

	public Map<Long, Integer> snapshot() {
		return Map.copyOf(this.details);
	}
}
