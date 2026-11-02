package dev.seedgenglobe.client;

import java.util.List;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.GlobeConfig.Option;
import dev.seedgenglobe.OptionSteps;

/**
 * A slider over the values of one setting (see {@link OptionSteps}): it snaps to the nearest value, shows it as
 * "Label: value", and writes it into the settings being edited. The arrow keys move one value at a time.
 */
final class OptionSlider extends AbstractSliderButton {

	private final Option<?> option;
	private final List<Object> values;
	private final GlobeConfig edit;
	private final Runnable changed;
	private int index;
	private boolean keyboard;

	OptionSlider(int x, int y, int width, int height, Option<?> option, List<Object> values, GlobeConfig edit, Runnable changed) {
		super(x, y, width, height, ConfigScreen.message(option, edit), OptionSteps.position(OptionSteps.index(values, ConfigScreen.get(option, edit)), values.size()));
		this.option = option;
		this.values = values;
		this.edit = edit;
		this.changed = changed;
		this.index = OptionSteps.index(values, ConfigScreen.get(option, edit));
		this.updateMessage();
	}

	/** Overrides AbstractSliderButton.updateMessage(). */
	protected void updateMessage() {
		// called by the super constructor too, before the fields are set
		if (this.option == null) return;
		this.setMessage(ConfigScreen.message(this.option, this.edit));
	}

	/** Overrides AbstractSliderButton.applyValue(): the value nearest the knob, and the knob onto that value. */
	protected void applyValue() {
		if (this.option == null) return;
		int count = this.values.size();
		int next = OptionSteps.moved(this.index, this.value, count, this.keyboard);
		this.value = OptionSteps.position(next, count);
		if (next != this.index) {
			this.index = next;
			ConfigScreen.set(this.option, this.edit, this.values.get(next));
			this.changed.run();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		this.keyboard = true;
		try {
			return super.keyPressed(event);
		}
		finally {
			this.keyboard = false;
		}
	}
}
