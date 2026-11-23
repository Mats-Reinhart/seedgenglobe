package dev.seedgenglobe.client;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.ModCompat;
import dev.seedgenglobe.SeedGenGlobe;
import dev.seedgenglobe.gen.GenProgress;
import dev.seedgenglobe.gen.SectionScheduler;

/**
 * A few lines in a corner of the screen with what SeedGenGlobe is doing (see {@link OverlayModel}). Shown while
 * sections are generated (or always, or never: {@code progressOverlay}), hidden with the rest of the HUD (F1).
 * The text is worked out twice a second, not every frame.
 */
final class ProgressOverlay {

	static final int MARGIN = 4, PADDING = 3, BACKGROUND = 0x90000000;
	/** Minecraft's font is 9 pixels high; one more between lines. */
	static final int LINE_HEIGHT = 10;

	private static List<OverlayModel.Line> lines = List.of();
	private static long builtAt;

	private ProgressOverlay() {}

	static void register() {
		try {
			HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(SeedGenGlobe.MOD_ID, "progress"), (graphics, deltaTracker) -> render(graphics));
		}
		catch (LinkageError error) {
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: this Fabric API has no HUD element registry; the progress overlay is off.", error);
		}
	}

	private static void render(GuiGraphicsExtractor graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || ClientScreens.hudHidden(minecraft)) return;
		long now = System.nanoTime();
		if (now - builtAt > 500_000_000L) {
			builtAt = now;
			try {
				lines = OverlayModel.lines(
					minecraft.level.dimension().identifier().toString(),
					GlobeConfig.get().progressOverlay,
					GenProgress.live(),
					SectionScheduler.get().snapshot(),
					dhReason()
				);
			}
			catch (RuntimeException | LinkageError error) {
				lines = List.of();
			}
		}
		List<OverlayModel.Line> shown = lines;
		if (shown.isEmpty()) return;
		Font font = minecraft.font;
		int lineHeight = LINE_HEIGHT;
		int width = 0;
		for (OverlayModel.Line line : shown) width = Math.max(width, font.width(line.text()));
		width += PADDING * 2;
		int height = shown.size() * lineHeight + PADDING * 2 - 1;
		GlobeConfig.OverlayPosition position = GlobeConfig.get().overlayPosition;
		int x = position.right() ? graphics.guiWidth() - width - MARGIN : MARGIN;
		int y = position.bottom() ? graphics.guiHeight() - height - MARGIN - (position.right() ? 0 : 40) : MARGIN;
		graphics.fill(x, y, x + width, y + height, BACKGROUND);
		for (int i = 0; i < shown.size(); i++) {
			OverlayModel.Line line = shown.get(i);
			graphics.text(font, line.text(), x + PADDING, y + PADDING + i * lineHeight, line.color(), false);
		}
	}

	/** Why Distant Horizons asks for few sections right now, or null. */
	private static @Nullable String dhReason() {
		if (!ModCompat.DISTANT_HORIZONS) return null;
		try {
			return dev.seedgenglobe.dh.DhPipeline.shortReason();
		}
		catch (LinkageError error) {
			return null;
		}
	}
}
