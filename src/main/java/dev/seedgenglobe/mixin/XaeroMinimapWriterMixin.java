package dev.seedgenglobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import xaero.common.minimap.write.MinimapWriter;

import dev.seedgenglobe.xaero.XaeroVertigoGuard;

/**
 * The same protection as {@link XaeroMapWriterMixin} for the minimap's own chunk writer, which Xaero's Minimap
 * uses when it does not take its chunks from the World Map.
 */
@Mixin(value = MinimapWriter.class, remap = false)
public abstract class XaeroMinimapWriterMixin {

	@WrapOperation(
		method = "writeTile",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
		require = 0
	)
	private ChunkAccess seedgenglobe$skipCutChunks(ClientLevel level, int chunkX, int chunkZ, ChunkStatus status, boolean load, Operation<ChunkAccess> original) {
		ChunkAccess chunk = original.call(level, chunkX, chunkZ, status, load);
		return chunk instanceof LevelChunk levelChunk && XaeroVertigoGuard.hidden(levelChunk) ? null : chunk;
	}
}
