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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import org.jetbrains.annotations.Nullable;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * SeedGenGlobe's own copy of the LOD surface map, one compressed file per Xaero region:
 * {@code r.<regionX>.<regionZ>.<detail>.<revision>.<maxSource>.bin}. It is kept apart from Xaero's files on purpose:
 * Xaero rebuilds singleplayer maps from the world save and only stores what it saw itself, so the LOD map is
 * re-applied from here whenever Xaero (re)loads a region, without recomputing any terrain. What a region holds is
 * part of its file name, so the store knows it for every region from the folder listing alone.
 * <p>
 * Thread safe. Disk access happens on the map builder thread only.
 */
public final class LodMapStore {

	/**
	 * What the store holds for a region.
	 *
	 * @param changedAt when its cells last changed this session (milliseconds), 0 for data from an earlier session
	 */
	public record Meta(int detail, long revision, int maxSource, long changedAt) {}

	final Path directory;
	private final ConcurrentHashMap<Long, Meta> metas = new ConcurrentHashMap<>();
	private final LinkedHashMap<Long, LodRegion> cache = new LinkedHashMap<>(32, 0.75F, true);
	/** Cells the memory cache may hold: about 15 bytes each. */
	private final long cacheCells;
	private long cachedCells;

	private LodMapStore(Path directory, long cacheCells) {
		this.directory = directory;
		this.cacheCells = cacheCells;
	}

	/** Opens (and creates) a store, reading which regions it holds from the file names. */
	static LodMapStore open(Path directory, long cacheCells) throws IOException {
		Files.createDirectories(directory);
		LodMapStore store = new LodMapStore(directory, cacheCells);
		Map<Long, List<Path>> stale = new HashMap<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "r.*.bin")) {
			for (Path file : files) {
				String[] parts = file.getFileName().toString().split("\\.");
				// r . x . z . detail . revision . maxSource . bin
				if (parts.length != 7) continue;
				try {
					int x = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]), detail = Integer.parseInt(parts[3]);
					long revision = Long.parseLong(parts[4]);
					int maxSource = Integer.parseInt(parts[5]);
					long key = LodRegion.key(x, z);
					Meta previous = store.metas.get(key);
					if (previous == null || revision > previous.revision()) {
						if (previous != null) stale.computeIfAbsent(key, k -> new ArrayList<>()).add(store.file(x, z, previous));
						store.metas.put(key, new Meta(detail, revision, maxSource, 0L));
					}
					else {
						stale.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
					}
				}
				catch (NumberFormatException ignored) {}
			}
		}
		// an interrupted save can leave the previous file of a region behind
		for (List<Path> files : stale.values()) {
			for (Path file : files) Files.deleteIfExists(file);
		}
		return store;
	}

	public int regionCount() {
		return this.metas.size();
	}

	public @Nullable Meta meta(int regionX, int regionZ) {
		return this.metas.get(LodRegion.key(regionX, regionZ));
	}

	/** @return the detail level stored for the region, or -1 when there is none */
	public int detail(int regionX, int regionZ) {
		Meta meta = this.meta(regionX, regionZ);
		return meta == null ? -1 : meta.detail();
	}

	/** The region's current data if it is in memory. */
	public @Nullable LodRegion cached(int regionX, int regionZ) {
		synchronized (this.cache) {
			return this.cache.get(LodRegion.key(regionX, regionZ));
		}
	}

	private void remember(LodRegion region) {
		synchronized (this.cache) {
			LodRegion previous = this.cache.put(LodRegion.key(region.regionX, region.regionZ), region);
			if (previous != null) this.cachedCells -= previous.size();
			this.cachedCells += region.size();
			while (this.cachedCells > this.cacheCells && this.cache.size() > 1) {
				Long eldest = this.cache.keySet().iterator().next();
				LodRegion removed = this.cache.remove(eldest);
				if (removed != null) this.cachedCells -= removed.size();
			}
		}
	}

	private Path file(int regionX, int regionZ, Meta meta) {
		return this.directory.resolve("r." + regionX + "." + regionZ + "." + meta.detail() + "." + meta.revision() + "." + meta.maxSource() + ".bin");
	}

	/** Builder thread: reads a region into the cache. @return null if the store has none (or it is unreadable) */
	@Nullable LodRegion load(int regionX, int regionZ) {
		Meta meta = this.meta(regionX, regionZ);
		if (meta == null) return null;
		LodRegion cached = this.cached(regionX, regionZ);
		if (cached != null && cached.revision == meta.revision()) return cached;
		Path file = this.file(regionX, regionZ, meta);
		try (InputStream raw = Files.newInputStream(file);
			DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw), 1 << 16))) {
			LodRegion region = LodRegionCodec.read(in, regionX, regionZ);
			if (region == null || region.revision != meta.revision()) {
				this.forget(regionX, regionZ, meta);
				return null;
			}
			this.remember(region);
			return region;
		}
		catch (IOException | RuntimeException exception) {
			SeedGenGlobe.LOGGER.debug("SeedGenGlobe: could not read LOD map region {}", file, exception);
			this.forget(regionX, regionZ, meta);
			return null;
		}
	}

	private void forget(int regionX, int regionZ, Meta meta) {
		this.metas.remove(LodRegion.key(regionX, regionZ), meta);
		synchronized (this.cache) {
			LodRegion removed = this.cache.remove(LodRegion.key(regionX, regionZ));
			if (removed != null) this.cachedCells -= removed.size();
		}
		try {
			Files.deleteIfExists(this.file(regionX, regionZ, meta));
		}
		catch (IOException ignored) {}
	}

	/**
	 * Builder thread: stores a new revision of a region, replacing the previous one.
	 *
	 * @param changedAt when its cells last changed (milliseconds)
	 * @return false when it could not be written
	 */
	boolean save(LodRegion region, long changedAt) {
		long key = LodRegion.key(region.regionX, region.regionZ);
		Meta meta = new Meta(region.detail, region.revision, region.maxSource, changedAt);
		Path file = this.file(region.regionX, region.regionZ, meta);
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (OutputStream raw = Files.newOutputStream(temporary);
				DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(raw), 1 << 16))) {
				LodRegionCodec.write(region, out);
			}
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			// cache first: the writer reads the meta, then the data
			this.remember(region);
			Meta previous = this.metas.put(key, meta);
			if (previous != null && !previous.equals(meta)) {
				Path old = this.file(region.regionX, region.regionZ, previous);
				if (!old.equals(file)) Files.deleteIfExists(old);
			}
			return true;
		}
		catch (IOException | RuntimeException exception) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: could not save LOD map region {}: {}", file, exception.toString());
			try {
				Files.deleteIfExists(temporary);
			}
			catch (IOException ignored) {}
			return false;
		}
	}

	public Map<Long, Meta> snapshot() {
		return Map.copyOf(this.metas);
	}
}
