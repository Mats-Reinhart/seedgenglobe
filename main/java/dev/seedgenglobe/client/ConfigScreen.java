package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.GlobeConfig.BoolOption;
import dev.seedgenglobe.GlobeConfig.Category;
import dev.seedgenglobe.GlobeConfig.ChoiceOption;
import dev.seedgenglobe.GlobeConfig.IntOption;
import dev.seedgenglobe.GlobeConfig.Option;

/**
 * SeedGenGlobe's settings in game (Mod Menu's config button). Made from {@link GlobeConfig#OPTIONS}: one page per
 * category, a button per setting that steps through its values, the setting's description as its tooltip. Edits a
 * copy of the settings; Done saves and applies it (most settings at once, the rest when a world loads again, as the
 * tooltip and the note at the bottom say), Cancel drops it. The Status button shows what SeedGenGlobe is doing.
 */
public final class ConfigScreen extends Screen {

	static final int ROW = 24, BUTTON_HEIGHT = 20, COLUMN_WIDTH = 200, GAP = 8;

	/** Values the buttons of number settings step through (the full range stays available in the file). */
	static final Map<String, int[]> STEPS = Map.of(
		"generatorThreads", new int[] { 0, 1, 2, 3, 4, 6, 8, 10, 12, 16, 24, 32 },
		"xaeroLodMapRadius", new int[] { 0, 64, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096 },
		"mapFullDetailRadius", new int[] { 0, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256 },
		"statsLogSeconds", new int[] { 0, 10, 30, 60, 120, 300, 600, 1800, 3600 },
		"structureDetail", new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 },
		"maxDetailLevel", new int[] { 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }
	);

	private final @Nullable Screen parent;
	private final GlobeConfig edit;
	private Category category = Category.GENERAL;

	public ConfigScreen(@Nullable Screen parent) {
		super(Component.literal("SeedGenGlobe"));
		this.parent = parent;
		this.edit = GlobeConfig.get().copy();
	}

	@Override
	public void init() {
		// category tabs, wrapped into rows
		int tabWidth = 90, tabs = Category.values().length;
		int perRow = Math.max(1, Math.min(tabs, (this.width - 2 * GAP) / (tabWidth + 4)));
		int rows = (tabs + perRow - 1) / perRow;
		int y = 24;
		for (int i = 0; i < tabs; i++) {
			Category tab = Category.values()[i];
			int row = i / perRow, column = i % perRow;
			int inRow = Math.min(perRow, tabs - row * perRow);
			int x = this.width / 2 - (inRow * (tabWidth + 4) - 4) / 2 + column * (tabWidth + 4);
			Button button = Button.builder(Component.literal(tab.label), pressed -> {
				this.category = tab;
				this.rebuildWidgets();
			}).bounds(x, y + row * ROW, tabWidth, BUTTON_HEIGHT).build();
			button.active = tab != this.category;
			this.addRenderableWidget(button);
		}
		// the settings of the category, in one or two columns
		List<Option<?>> options = new ArrayList<>();
		for (Option<?> option : GlobeConfig.OPTIONS) {
			if (option.category == this.category && !option.key.equals("generatorPriority")) options.add(option);
		}
		int columns = this.width >= 2 * COLUMN_WIDTH + GAP + 20 && options.size() > 4 ? 2 : 1;
		int top = y + rows * ROW + 8;
		int left = this.width / 2 - (columns * COLUMN_WIDTH + (columns - 1) * GAP) / 2;
		int perColumn = (options.size() + columns - 1) / columns;
		for (int i = 0; i < options.size(); i++) {
			int column = i / perColumn, row = i % perColumn;
			this.addRenderableWidget(this.button(options.get(i), left + column * (COLUMN_WIDTH + GAP), top + row * ROW));
		}
		// bottom row
		int bottom = this.height - 28;
		int width = 98;
		int x = this.width / 2 - (4 * width + 3 * 4) / 2;
		this.addRenderableWidget(tooltip(Button.builder(Component.literal("Status..."), pressed -> Minecraft.getInstance().setScreen(new StatusScreen(this)))
			.bounds(x, bottom, width, BUTTON_HEIGHT).build(), "What SeedGenGlobe is doing right now."));
		this.addRenderableWidget(tooltip(Button.builder(Component.literal("Page defaults"), pressed -> {
			for (Option<?> option : GlobeConfig.OPTIONS) {
				if (option.category == this.category) resetValue(option, this.edit);
			}
			this.rebuildWidgets();
		}).bounds(x + width + 4, bottom, width, BUTTON_HEIGHT).build(), "Puts the settings of this page back to their defaults."));
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), pressed -> this.onClose()).bounds(x + 2 * (width + 4), bottom, width, BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), pressed -> {
			GlobeConfig.apply(this.edit);
			this.onClose();
		}).bounds(x + 3 * (width + 4), bottom, width, BUTTON_HEIGHT).build());
	}

	/** Sets a tooltip on a widget (through AbstractWidget, where the method is declared). */
	static Button tooltip(Button button, String text) {
		AbstractWidget widget = button;
		widget.setTooltip(Tooltip.create(Component.literal(text)));
		return button;
	}

	private static <T> void resetValue(Option<T> option, GlobeConfig config) {
		option.set(config, option.defaultValue());
	}

	private Button button(Option<?> option, int x, int y) {
		Button button = Button.builder(this.message(option), pressed -> {
			this.step(option);
			pressed.setMessage(this.message(option));
		}).bounds(x, y, COLUMN_WIDTH, BUTTON_HEIGHT).build();
		return tooltip(button, option.description + (option.apply.note.isEmpty() ? "" : "\n\n" + option.apply.note));
	}

	private Component message(Option<?> option) {
		return Component.literal(option.label + ": " + this.valueText(option));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private String valueText(Option option) {
		return option.display(option.get(this.edit));
	}

	/** Moves a setting to its next value. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void step(Option<?> option) {
		if (option instanceof BoolOption bool) {
			bool.set(this.edit, !bool.get(this.edit));
		}
		else if (option instanceof ChoiceOption choice) {
			choice.set(this.edit, choice.next((Enum) choice.get(this.edit)));
		}
		else if (option instanceof IntOption number) {
			int[] steps = STEPS.get(number.key);
			int value = number.get(this.edit);
			if (steps == null) {
				number.set(this.edit, value >= number.max ? number.min : value + 1);
				return;
			}
			int next = steps[0];
			for (int step : steps) {
				if (step > value) {
					next = step;
					break;
				}
			}
			number.set(this.edit, next);
		}
	}

	/** Whether a setting that only applies when a world loads again (or after a restart) was changed. */
	private @Nullable String pendingNote() {
		GlobeConfig current = GlobeConfig.get();
		boolean rejoin = false, restart = false;
		for (Option<?> option : GlobeConfig.OPTIONS) {
			if (option.apply == GlobeConfig.Apply.LIVE) continue;
			if (!same(option, current, this.edit)) {
				if (option.apply == GlobeConfig.Apply.RESTART) restart = true;
				else rejoin = true;
			}
		}
		if (restart) return "Some changes take effect after restarting the game.";
		if (rejoin) return "Some changes take effect when a world is loaded again.";
		return null;
	}

	private static <T> boolean same(Option<T> option, GlobeConfig a, GlobeConfig b) {
		return option.get(a).equals(option.get(b));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.getFont(), "SeedGenGlobe", this.width / 2, 8, 0xFFFFFFFF);
		String note = this.pendingNote();
		if (note != null) graphics.centeredText(this.getFont(), note, this.width / 2, this.height - 42, 0xFFFFCC55);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(this.parent);
	}
}
