package dev.seedgenglobe.dh;

/** Added to Distant Horizons' multiplayer client level by {@code DhClientLevelMixin}. */
public interface DhClientLevelHook {

	/** Whether SeedGenGlobe answers this level's section requests itself (multiplayer client generation). */
	boolean seedgenglobe$generatesLocally();
}
