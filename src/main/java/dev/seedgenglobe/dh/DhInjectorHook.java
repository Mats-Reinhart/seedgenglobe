package dev.seedgenglobe.dh;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;

/** Added to Distant Horizons' world generator registry by {@code WorldGeneratorInjectorMixin}. */
public interface DhInjectorHook {

	/**
	 * Removes a generator from a level's overrides, and the level from the registry once no generator is left for it.
	 *
	 * @return whether the generator was bound to the level
	 */
	boolean seedgenglobe$unbind(IDhApiLevelWrapper level, IDhApiWorldGenerator generator);
}
