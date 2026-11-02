package dev.seedgenglobe.mixin;

import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.terraformersmc.modmenu.gui.widget.DescriptionListWidget;
import com.terraformersmc.modmenu.util.mod.Mod;

import net.minecraft.network.chat.Component;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * Shows SeedGenGlobe's credits in Mod Menu as "Credits:" followed by the names, without the role heading
 * ("Authors:") Mod Menu puts between them. Only SeedGenGlobe's own entry changes.
 * <p>
 * Mod Menu lists each role of a mod's credits as a heading (its {@code creditsRoleText}, which always ends in a colon)
 * with the people of that role under it. For SeedGenGlobe each person becomes a "role" of their own with nobody under
 * it, and that role's heading is the plain name. {@code GlobeMixinPlugin} only applies this when both calls are where
 * Mod Menu 18 has them, so a different Mod Menu shows its usual "Authors:" heading instead of anything half changed.
 */
@Mixin(value = DescriptionListWidget.class, remap = false)
public abstract class ModMenuCreditsMixin {

	/** Marks a credits key that is a name to show as it is. */
	@Unique
	private static final String SEEDGENGLOBE$NAME = "\u0000seedgenglobe:";

	@WrapOperation(
		method = "rebuildUI",
		at = @At(value = "INVOKE", target = "Lcom/terraformersmc/modmenu/util/mod/Mod;getCredits()Ljava/util/SortedMap;"),
		require = 0
	)
	private SortedMap<String, Set<String>> seedgenglobe$namesOnly(Mod mod, Operation<SortedMap<String, Set<String>>> original) {
		SortedMap<String, Set<String>> credits = original.call(mod);
		if (credits == null || credits.isEmpty() || !SeedGenGlobe.MOD_ID.equals(mod.getId())) return credits;
		SortedMap<String, Set<String>> names = new TreeMap<>();
		for (Set<String> people : credits.values()) {
			for (String person : people) names.put(SEEDGENGLOBE$NAME + person, new TreeSet<>());
		}
		return names.isEmpty() ? credits : names;
	}

	@WrapOperation(
		method = "rebuildUI",
		at = @At(value = "INVOKE", target = "Lcom/terraformersmc/modmenu/gui/widget/DescriptionListWidget;creditsRoleText(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;"),
		require = 0
	)
	private Component seedgenglobe$plainName(DescriptionListWidget widget, String role, Operation<Component> original) {
		if (role != null && role.startsWith(SEEDGENGLOBE$NAME)) return Component.literal(role.substring(SEEDGENGLOBE$NAME.length()));
		return original.call(widget, role);
	}
}
