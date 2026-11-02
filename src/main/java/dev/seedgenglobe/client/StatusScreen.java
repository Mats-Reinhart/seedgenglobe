package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * What SeedGenGlobe is doing right now ({@link StatusReport}), refreshed every second: generators, threads, Distant
 * Horizons' queues, the Xaero LOD map, and anything that could not be set up. Opened from the config screen.
 */
public final class StatusScreen extends Screen {

	static final int LINE = 10, TOP = 24, BOTTOM = 34;

	private final @Nullable Screen parent;
	private List<String> lines = List.of();
	private int scroll, ticks;

	public StatusScreen(@Nullable Screen parent) {
		super(Component.literal("SeedGenGlobe status"));
		this.parent = parent;
	}

	@Override
	public void init() {
		this.refresh();
		// four buttons, narrower in a small window so they always fit
		int y = this.height - 28, width = Math.max(36, Math.min(70, (this.width - 12 - 3 * 4) / 4));
		int x = this.width / 2 - (4 * width + 3 * 4) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("Up"), pressed -> this.scroll(-this.visible() / 2)).bounds(x, y, width, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Down"), pressed -> this.scroll(this.visible() / 2)).bounds(x + width + 4, y, width, 20).build());
		this.addRenderableWidget(ConfigScreen.tooltip(Button.builder(Component.literal("To log"), pressed -> {
			for (String line : StatusReport.lines(Minecraft.getInstance())) dev.seedgenglobe.SeedGenGlobe.LOGGER.info("SeedGenGlobe status: {}", line);
		}).bounds(x + 2 * (width + 4), y, width, 20).build(), "Writes this status into the game log (latest.log), to share it."));
		this.addRenderableWidget(Button.builder(Component.literal("Back"), pressed -> this.onClose()).bounds(x + 3 * (width + 4), y, width, 20).build());
	}

	private int visible() {
		return Math.max(1, (this.height - TOP - BOTTOM - 8) / LINE);
	}

	private void scroll(int by) {
		this.scroll = Math.max(0, Math.min(Math.max(0, this.lines.size() - this.visible()), this.scroll + by));
	}

	private void refresh() {
		List<String> wrapped = new ArrayList<>();
		int width = Math.max(100, this.width - 20);
		try {
			for (String line : StatusReport.lines(Minecraft.getInstance())) wrap(line, width, wrapped);
		}
		catch (RuntimeException | LinkageError error) {
			wrapped.add("Could not read the status: " + error);
		}
		this.lines = wrapped;
		this.scroll(0);
	}

	/** Breaks a line at spaces to fit the width, indenting continuation lines. */
	private void wrap(String line, int width, List<String> out) {
		String rest = line;
		boolean first = true;
		while (!rest.isEmpty()) {
			String prefix = first ? "" : "    ";
			if (this.getFont().width(prefix + rest) <= width) {
				out.add(prefix + rest);
				return;
			}
			int cut = rest.length();
			while (cut > 1 && this.getFont().width(prefix + rest.substring(0, cut)) > width) cut--;
			int space = rest.lastIndexOf(' ', cut);
			if (space > 0) cut = space;
			out.add(prefix + rest.substring(0, cut));
			rest = rest.substring(cut).stripLeading();
			first = false;
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (++this.ticks % 20 == 0) this.refresh();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.getFont(), "SeedGenGlobe status", this.width / 2, 8, 0xFFFFFFFF);
		int visible = this.visible();
		for (int i = 0; i < visible && this.scroll + i < this.lines.size(); i++) {
			graphics.text(this.getFont(), this.lines.get(this.scroll + i), 10, TOP + i * LINE, 0xFFE0E0E0, false);
		}
		if (this.lines.size() > visible) {
			String position = (this.scroll + 1) + "-" + Math.min(this.lines.size(), this.scroll + visible) + " of " + this.lines.size();
			graphics.text(this.getFont(), position, this.width - 10 - this.getFont().width(position), 8, 0xFFAAAAAA, false);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		this.scroll(scrollY > 0.0D ? -3 : 3);
		return true;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(this.parent);
	}
}
