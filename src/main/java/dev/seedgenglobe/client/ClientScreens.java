package dev.seedgenglobe.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

import dev.seedgenglobe.SeedGenGlobe;

/**
 * Opening screens and asking whether the HUD is hidden, on Minecraft 26.1 and 26.2 alike.
 * <p>
 * Minecraft 26.2 moved these three members, and nothing else SeedGenGlobe uses:
 * <ul>
 * <li>{@code Minecraft.setScreen(Screen)} became {@code Minecraft.gui.setScreen(Screen)},</li>
 * <li>the field {@code Minecraft.screen} became {@code Minecraft.gui.screen()},</li>
 * <li>the field {@code Options.hideGui} became {@code Minecraft.gui.hud.isHidden()} (26.1's {@code Gui}, the in-game
 * HUD, is 26.2's {@code Hud}; 26.2's {@code Gui} holds the screen, the overlay and the HUD).</li>
 * </ul>
 * They are looked up by name once, so the same jar runs on both versions; a member that is on neither (a later
 * Minecraft) is logged once and then does nothing, instead of failing the screens that use it.
 */
final class ClientScreens {

	private static final @Nullable MethodHandle SET_SCREEN, CURRENT_SCREEN, HUD_HIDDEN;
	
	static {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		SET_SCREEN = first(
			"Minecraft.setScreen",
			() -> lookup.findVirtual(Minecraft.class, "setScreen", MethodType.methodType(void.class, Screen.class)),
			() -> MethodHandles.filterArguments(lookup.findVirtual(Gui.class, "setScreen", MethodType.methodType(void.class, Screen.class)), 0, gui(lookup))
		);
		CURRENT_SCREEN = first(
			"Minecraft.screen",
			() -> lookup.findGetter(Minecraft.class, "screen", Screen.class),
			() -> MethodHandles.filterReturnValue(gui(lookup), lookup.findVirtual(Gui.class, "screen", MethodType.methodType(Screen.class)))
		);
		HUD_HIDDEN = first(
			"Options.hideGui",
			() -> MethodHandles.filterReturnValue(lookup.findGetter(Minecraft.class, "options", Options.class), lookup.findGetter(Options.class, "hideGui", boolean.class)),
			() -> {
				// 26.2's Hud does not exist on 26.1, so it is only named here
				Class<?> hud = Class.forName("net.minecraft.client.gui.Hud", false, ClientScreens.class.getClassLoader());
				MethodHandle toHud = MethodHandles.filterReturnValue(gui(lookup), lookup.findGetter(Gui.class, "hud", hud));
				return MethodHandles.filterReturnValue(toHud, lookup.findVirtual(hud, "isHidden", MethodType.methodType(boolean.class)));
			}
		);
	}

	private ClientScreens() {}

	@FunctionalInterface
	private interface Finder {
		MethodHandle find() throws ReflectiveOperationException;
	}

	private static MethodHandle gui(MethodHandles.Lookup lookup) throws ReflectiveOperationException {
		return lookup.findGetter(Minecraft.class, "gui", Gui.class);
	}

	/** The first of the two ways (26.1's, then 26.2's) this Minecraft has, or null when it has neither. */
	private static @Nullable MethodHandle first(String what, Finder older, Finder newer) {
		for (Finder finder : new Finder[] { older, newer }) {
			try {
				return finder.find();
			}
			catch (ReflectiveOperationException | RuntimeException | LinkageError notThisVersion) {
				// try the other one
			}
		}
		SeedGenGlobe.LOGGER.warn("SeedGenGlobe: this Minecraft has neither 26.1's nor 26.2's {}; what needs it is left out.", what);
		return null;
	}

	/** Shows a screen ({@code null} closes the current one), like {@code Minecraft.setScreen}. */
	static void open(Minecraft minecraft, @Nullable Screen screen) {
		if (SET_SCREEN == null) return;
		try {
			SET_SCREEN.invoke(minecraft, screen);
		}
		catch (RuntimeException | Error passed) {
			throw passed;
		}
		catch (Throwable impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** The screen that is open, or null. */
	static @Nullable Screen current(Minecraft minecraft) {
		if (CURRENT_SCREEN == null) return null;
		try {
			return (Screen)(CURRENT_SCREEN.invoke(minecraft));
		}
		catch (RuntimeException | Error passed) {
			throw passed;
		}
		catch (Throwable impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** Whether the HUD is hidden (F1). */
	static boolean hudHidden(Minecraft minecraft) {
		if (HUD_HIDDEN == null) return false;
		try {
			return (boolean)(HUD_HIDDEN.invoke(minecraft));
		}
		catch (RuntimeException | Error passed) {
			throw passed;
		}
		catch (Throwable impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
