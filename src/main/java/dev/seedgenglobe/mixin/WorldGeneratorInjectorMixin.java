package dev.seedgenglobe.mixin;

import java.util.HashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector;

import dev.seedgenglobe.dh.DhInjectorHook;

/**
 * Lets SeedGenGlobe take its generator back out of Distant Horizons' registry when the level it was made for goes away.
 * <p>
 * Distant Horizons 3.0.3 keeps every registered generator in a static map keyed by the level's wrapper and has no way
 * to unregister one, nor does it clear the map, so each world opened in a game session kept its generator, and through
 * the wrapper its stopped server level, in memory until the game was closed.
 */
@Mixin(value = WorldGeneratorInjector.class, remap = false)
public abstract class WorldGeneratorInjectorMixin implements DhInjectorHook {

	@Shadow @Final private HashMap<IDhApiLevelWrapper, OverrideInjector> worldGeneratorByLevelWrapper;

	@Override
	public boolean seedgenglobe$unbind(IDhApiLevelWrapper level, IDhApiWorldGenerator generator) {
		OverrideInjector overrides = this.worldGeneratorByLevelWrapper.get(level);
		if (overrides == null) return false;
		if (overrides.get(IDhApiWorldGenerator.class) != generator) return false;
		overrides.unbind(IDhApiWorldGenerator.class, generator);
		if (overrides.get(IDhApiWorldGenerator.class) == null) this.worldGeneratorByLevelWrapper.remove(level);
		return true;
	}
}
