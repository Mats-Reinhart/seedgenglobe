package dev.seedgenglobe.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/**
 * The scrolling part of the config screen: rows of buttons and sliders, like the list of Minecraft's own option
 * screens. Minecraft draws the list's background, its top and bottom edges and, when the rows do not fit, a scrollbar,
 * and routes clicks, dragging and scrolling to the widgets of the row under the mouse.
 * <p>
 * The widgets keep the x position the screen gave them; each row moves its widgets to its own y position as it is
 * drawn, so they follow the scrolling.
 */
final class OptionList extends ContainerObjectSelectionList<OptionList.Row> {

	private final int rowWidth;

	/**
	 * @param y top of the list
	 * @param rowWidth width of the widest row (the scrollbar goes to its right)
	 */
	OptionList(Minecraft minecraft, int width, int height, int y, int rowHeight, int rowWidth) {
		super(minecraft, width, height, y, rowHeight);
		this.rowWidth = rowWidth;
		// rows from the top, not centred when they do not fill the list
		this.centerListVertically = false;
	}

	/** Adds a row of widgets (already sized and placed horizontally). */
	void add(List<AbstractWidget> widgets) {
		this.addEntry(new Row(widgets));
	}

	/** Overrides AbstractSelectionList.getRowWidth(): the rows are as wide as the widest one, plus a little room. */
	public int getRowWidth() {
		return this.rowWidth + 4;
	}

	/** One row: up to a few widgets side by side. */
	static final class Row extends ContainerObjectSelectionList.Entry<Row> {

		private final List<AbstractWidget> widgets;

		Row(List<AbstractWidget> widgets) {
			this.widgets = new ArrayList<>(widgets);
		}

		/** Overrides AbstractSelectionList.Entry.extractContent: draws the widgets at the row's position. */
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			int y = this.getY();
			for (AbstractWidget widget : this.widgets) {
				widget.setY(y);
				widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
			}
		}

		/** Overrides ContainerObjectSelectionList.Entry.children(). */
		@SuppressWarnings({ "rawtypes", "unchecked" })
		public List children() {
			return this.widgets;
		}

		/** Overrides ContainerObjectSelectionList.Entry.narratables(): the widgets are narratable themselves. */
		@SuppressWarnings({ "rawtypes", "unchecked" })
		public List narratables() {
			return this.widgets;
		}
	}
}
