package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import xaero.map.MapWriter;

import dev.seedgenglobe.xaero.XaeroVertigoGuard;

/**
 * Keeps Xaero's World Map from mapping chunks that Vertigo only partly synced.
 * <p>
 * Vertigo sends the client only the chunk sections near the player's height. A chunk whose surface lies outside
 * that range looks like empty air to the client, and Xaero's World Map would record it as a black, empty chunk at
 * the bottom of the world (shown with y = -1024 and the plains biome of an unsynced section). Such a chunk is now
 * reported as not loaded, so Xaero leaves the map alone and maps the chunk properly once its surface arrives.
 */
@Mixin(value = MapWriter.class, remap = false)
public abstract class XaeroMapWriterMixin {

	@WrapOperation(
		method = "writeChunk",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
		require = 0
	)
	private ChunkAccess seedgenglobe$skipCutChunks(Level level, int chunkX, int chunkZ, ChunkStatus status, boolean load, Operation<ChunkAccess> original) {
		ChunkAccess chunk = original.call(level, chunkX, chunkZ, status, load);
		return chunk instanceof LevelChunk levelChunk && XaeroVertigoGuard.hidden(levelChunk) ? null : chunk;
	}
}
