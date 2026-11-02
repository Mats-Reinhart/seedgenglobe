package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.GlobeConfig.Category;
import dev.seedgenglobe.GlobeConfig.Option;
import dev.seedgenglobe.OptionSteps;

/**
 * SeedGenGlobe's settings in game (Mod Menu's config button). Made from {@link GlobeConfig#OPTIONS}: one page per
 * category, the setting's description as its tooltip. Settings with more than three values get a slider, the others a
 * button that steps through them (see {@link OptionSteps}).
 * <p>
 * The master switch ({@code enabled}) is not on a page: it is the Enabled / Disabled button right after the page
 * buttons, in green or red, so it can be seen from every page. The General page also has the "Regenerate LODs..."
 * button ({@link RegenerateLods}).
 * <p>
 * Laid out like Minecraft's own option screens: the title (and the Status button, top right) stays at the top, the
 * Page defaults, Cancel and Save &amp; quit buttons stay at the bottom, and the page buttons and settings between them
 * are one list that scrolls when the window is too small to show it all.
 * <p>
 * Edits a copy of the settings; Save &amp; quit saves and applies it (most settings at once, the rest when a world
 * loads again, as the tooltip and the note above the buttons say), Cancel or Escape drops it.
 */
public final class ConfigScreen extends Screen {

	static final int HEADER = 28, ROW = 24, BUTTON_HEIGHT = 20, COLUMN_WIDTH = 200, GAP = 8;
	static final int TAB_WIDTH = 90, MIN_TAB_WIDTH = 70, TAB_GAP = 4;
	/** Two columns of settings once each can be this wide (at Minecraft's default window size, 427 GUI pixels, they are 193). */
	static final int MIN_TWO_COLUMN_WIDTH = 180;
	/** Room left on each side of the list's rows, for the scrollbar. */
	static final int SIDE = 16;
	/** Footer: the buttons, and above them a line for the "takes effect when..." note when the window is tall enough. */
	static final int FOOTER = 32, NOTE_LINE = 11, NOTE_MIN_HEIGHT = 200;

	private final @Nullable Screen parent;
	private final GlobeConfig edit;
	private Category category = Category.GENERAL;
	private @Nullable Button save;

	public ConfigScreen(@Nullable Screen parent) {
		super(Component.literal("SeedGenGlobe"));
		this.parent = parent;
		this.edit = GlobeConfig.get().copy();
	}

	private int footerHeight() {
		return this.height >= NOTE_MIN_HEIGHT ? FOOTER + NOTE_LINE : FOOTER;
	}

	@Override
	public void init() {
		Minecraft minecraft = Minecraft.getInstance();

		// header: the Status button in the top right corner (the title is drawn in extractRenderState)
		int statusWidth = Math.max(44, Math.min(70, this.width / 4));
		this.addRenderableWidget(tooltip(
			Button.builder(Component.literal("Status..."), pressed -> minecraft.setScreen(new StatusScreen(this)))
				.bounds(this.width - statusWidth - 6, 4, statusWidth, BUTTON_HEIGHT).build(),
			"What SeedGenGlobe is doing right now."
		));

		// the list between header and footer: page buttons, then the settings of the page
		int footer = this.footerHeight();
		int available = Math.max(60, this.width - 2 * SIDE);
		List<Option<?>> options = new ArrayList<>();
		for (Option<?> option : GlobeConfig.OPTIONS) {
			if (option.category == this.category && onPage(option)) options.add(option);
		}
		// the General page ends with the Regenerate LODs button, laid out like a setting
		boolean regenerate = this.category == Category.GENERAL;
		int cells = options.size() + (regenerate ? 1 : 0);
		int columns = available >= 2 * MIN_TWO_COLUMN_WIDTH + GAP && cells > 4 ? 2 : 1;
		int columnWidth = Math.min(COLUMN_WIDTH, columns == 2 ? (available - GAP) / 2 : available);
		int optionsWidth = columns * columnWidth + (columns - 1) * GAP;
		int left = this.width / 2 - optionsWidth / 2;
		// the page buttons are laid out for the window, not the page, so they stay put when the page changes
		int tabsWidth = Math.min(available, 2 * COLUMN_WIDTH + GAP);
		OptionList list = new OptionList(minecraft, this.width, Math.max(ROW, this.height - HEADER - footer), HEADER, ROW, Math.max(tabsWidth, optionsWidth));

		Category[] categories = Category.values();
		// the page buttons, then the Enabled / Disabled switch in the slot right after the last of them
		int slots = categories.length + 1;
		// as many full-width page buttons as fit, but two narrower ones rather than one per row while they can be 70 wide
		int perRow = Math.max(1, Math.min(slots, Math.max(
			(tabsWidth + TAB_GAP) / (TAB_WIDTH + TAB_GAP),
			Math.min(2, (tabsWidth + TAB_GAP) / (MIN_TAB_WIDTH + TAB_GAP))
		)));
		for (int first = 0; first < slots; first += perRow) {
			int inRow = Math.min(perRow, slots - first);
			int tabWidth = Math.min(TAB_WIDTH, (tabsWidth - (perRow - 1) * TAB_GAP) / perRow);
			int x = this.width / 2 - (inRow * (tabWidth + TAB_GAP) - TAB_GAP) / 2;
			List<AbstractWidget> tabs = new ArrayList<>();
			for (int i = 0; i < inRow; i++) {
				int tabX = x + i * (tabWidth + TAB_GAP);
				if (first + i == categories.length) {
					tabs.add(this.enabledSwitch(tabX, tabWidth));
					continue;
				}
				Category tab = categories[first + i];
				Button button = Button.builder(Component.literal(tab.label), pressed -> {
					this.category = tab;
					this.rebuildWidgets();
				}).bounds(tabX, 0, tabWidth, BUTTON_HEIGHT).build();
				button.active = tab != this.category;
				tabs.add(button);
			}
			list.add(tabs);
		}
		for (int first = 0; first < cells; first += columns) {
			List<AbstractWidget> row = new ArrayList<>();
			for (int column = 0; column < columns && first + column < cells; column++) {
				int cell = first + column, cellX = left + column * (columnWidth + GAP);
				row.add(cell < options.size() ? this.widget(options.get(cell), cellX, columnWidth) : RegenerateLods.button(this, cellX, columnWidth));
			}
			list.add(row);
		}
		this.addRenderableWidget(list);

		// footer: Page defaults, Cancel, Save & quit, as wide as the window allows
		int bottom = this.height - 26;
		int buttonWidth = Math.max(40, Math.min(98, (this.width - 12 - 2 * 4) / 3));
		int x = this.width / 2 - (3 * buttonWidth + 2 * 4) / 2;
		this.addRenderableWidget(tooltip(Button.builder(Component.literal("Page defaults"), pressed -> {
			for (Option<?> option : GlobeConfig.OPTIONS) {
				if (option.category == this.category && onPage(option)) resetValue(option, this.edit);
			}
			this.rebuildWidgets();
		}).bounds(x, bottom, buttonWidth, BUTTON_HEIGHT).build(), "Puts the settings of this page back to their defaults."));
		this.addRenderableWidget(tooltip(
			Button.builder(Component.literal("Cancel"), pressed -> this.onClose()).bounds(x + buttonWidth + 4, bottom, buttonWidth, BUTTON_HEIGHT).build(),
			"Leaves without saving: the settings stay as they were."
		));
		this.save = Button.builder(Component.literal("Save & quit"), pressed -> {
			GlobeConfig.apply(this.edit);
			this.onClose();
		}).bounds(x + 2 * (buttonWidth + 4), bottom, buttonWidth, BUTTON_HEIGHT).build();
		this.addRenderableWidget(this.save);
		this.updateSaveTooltip();
	}

	/**
	 * Whether a setting is shown on its page: the master switch has its own button next to the page buttons, and
	 * {@code generatorPriority} is only in the file.
	 */
	static boolean onPage(Option<?> option) {
		return !option.key.equals(ENABLED) && !option.key.equals("generatorPriority");
	}

	static final String ENABLED = "enabled";

	/** The master switch, after the page buttons: "Enabled" in green or "Disabled" in red, for the edited settings. */
	private Button enabledSwitch(int x, int width) {
		Option<?> option = GlobeConfig.option(ENABLED);
		Button button = Button.builder(enabledMessage(this.edit.enabled), pressed -> {
			this.edit.enabled = !this.edit.enabled;
			pressed.setMessage(enabledMessage(this.edit.enabled));
			this.updateSaveTooltip();
		}).bounds(x, 0, width, BUTTON_HEIGHT).build();
		return tooltip(button, option.description + "\n\n" + option.apply.note);
	}

	static Component enabledMessage(boolean enabled) {
		return Component.literal(enabled ? "Enabled" : "Disabled").withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	/** Builds the screen again (the Regenerate LODs button after its outcome arrived). */
	void refresh() {
		this.rebuildWidgets();
	}

	/** Sets a tooltip on a widget (through AbstractWidget, where the method is declared). */
	static <W extends AbstractWidget> W tooltip(W widget, String text) {
		AbstractWidget plain = widget;
		plain.setTooltip(Tooltip.create(Component.literal(text)));
		return widget;
	}

	private static <T> void resetValue(Option<T> option, GlobeConfig config) {
		option.set(config, option.defaultValue());
	}

	/** A slider for a setting with more than three values, a button that steps through them otherwise. */
	private AbstractWidget widget(Option<?> option, int x, int width) {
		List<Object> values = OptionSteps.values(option, get(option, this.edit));
		String tooltip = option.description + (option.apply.note.isEmpty() ? "" : "\n\n" + option.apply.note);
		if (OptionSteps.slider(values)) {
			return tooltip(new OptionSlider(x, 0, width, BUTTON_HEIGHT, option, values, this.edit, this::updateSaveTooltip), tooltip);
		}
		Button button = Button.builder(message(option, this.edit), pressed -> {
			set(option, this.edit, OptionSteps.next(values, get(option, this.edit)));
			pressed.setMessage(message(option, this.edit));
			this.updateSaveTooltip();
		}).bounds(x, 0, width, BUTTON_HEIGHT).build();
		return tooltip(button, tooltip);
	}

	static Component message(Option<?> option, GlobeConfig config) {
		return Component.literal(option.label + ": " + valueText(option, get(option, config)));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	static String valueText(Option option, Object value) {
		return option.display(value);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	static Object get(Option option, GlobeConfig config) {
		return option.get(config);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	static void set(Option option, GlobeConfig config, Object value) {
		option.set(config, value);
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

	/** The Save & quit tooltip carries the note too, for windows too short to show it above the buttons. */
	private void updateSaveTooltip() {
		Button save = this.save;
		if (save == null) return;
		String note = this.pendingNote();
		tooltip(save, "Saves the settings and closes this screen." + (note != null ? "\n\n" + note : ""));
	}

	private static <T> boolean same(Option<T> option, GlobeConfig a, GlobeConfig b) {
		return option.get(a).equals(option.get(b));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// the title, centred unless that would run into the Status button
		int titleWidth = this.getFont().width("SeedGenGlobe");
		int statusLeft = this.width - Math.max(44, Math.min(70, this.width / 4)) - 6;
		if (this.width / 2 + titleWidth / 2 + 4 <= statusLeft) graphics.centeredText(this.getFont(), "SeedGenGlobe", this.width / 2, 10, 0xFFFFFFFF);
		else if (6 + titleWidth + 4 <= statusLeft) graphics.text(this.getFont(), "SeedGenGlobe", 6, 10, 0xFFFFFFFF, true);
		if (this.height >= NOTE_MIN_HEIGHT) {
			String note = this.pendingNote();
			if (note != null) graphics.centeredText(this.getFont(), note, this.width / 2, this.height - FOOTER - NOTE_LINE + 4, 0xFFFFCC55);
		}
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(this.parent);
	}
}
