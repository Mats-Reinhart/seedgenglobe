package dev.seedgenglobe.mixin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import dev.seedgenglobe.structures.CaptureGuard;

/**
 * While a LOD section's structures are being placed, the real level's structure manager answers "no structure here".
 * <p>
 * Structure pieces placed into a LOD section sometimes ask the real level about structures: a swamp hut's cat, for
 * one, checks whether it stands in a swamp hut to pick its colour. In a Big Globe world that question runs Big Globe's
 * own structure locator for a spot thousands of blocks from any player, in parallel on Java's shared thread pool at
 * normal priority, filling Big Globe's real caches. Such answers never change what a LOD shows, so they are skipped.
 * Every other caller (the game, other mods, real chunk generation) is untouched: the guard only holds on the thread
 * that is placing a LOD section's structures (see {@link CaptureGuard}).
 */
@Mixin(value = StructureManager.class, remap = false)
public abstract class StructureManagerCaptureMixin {

	@Inject(
		method = {
			"getStructureAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
			"getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
			"getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/tags/TagKey;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
			"getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderSet;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
			"getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
			"getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;"
		},
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void seedgenglobe$noStructure(CallbackInfoReturnable<StructureStart> callback) {
		if (!CaptureGuard.active()) return;
		StructureStart none = CaptureGuard.noStructure();
		if (none != null) callback.setReturnValue(none);
	}

	@Inject(
		method = {
			"hasAnyStructureAt(Lnet/minecraft/core/BlockPos;)Z",
			"structureHasPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/StructureStart;)Z"
		},
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void seedgenglobe$noStructureHere(CallbackInfoReturnable<Boolean> callback) {
		if (CaptureGuard.active()) callback.setReturnValue(Boolean.FALSE);
	}

	@Inject(method = "getAllStructuresAt(Lnet/minecraft/core/BlockPos;)Ljava/util/Map;", at = @At("HEAD"), cancellable = true, require = 0)
	private void seedgenglobe$noStructures(CallbackInfoReturnable<Map<?, ?>> callback) {
		if (CaptureGuard.active()) callback.setReturnValue(Collections.emptyMap());
	}

	@Inject(
		method = {
			"startsForStructure(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Predicate;)Ljava/util/List;",
			"startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;"
		},
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void seedgenglobe$noStarts(CallbackInfoReturnable<List<?>> callback) {
		if (CaptureGuard.active()) callback.setReturnValue(List.of());
	}
}
