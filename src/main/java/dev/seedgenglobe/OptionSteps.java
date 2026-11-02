package dev.seedgenglobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import dev.seedgenglobe.GlobeConfig.BoolOption;
import dev.seedgenglobe.GlobeConfig.ChoiceOption;
import dev.seedgenglobe.GlobeConfig.IntOption;
import dev.seedgenglobe.GlobeConfig.Option;

/**
 * The values the config screen offers for each setting, and how a slider position maps onto them. Kept apart from
 * the screen (which needs Minecraft) so the offline tests can check it.
 * <p>
 * A setting with more than three values gets a slider over them, one with two or three a button that steps through
 * them. Number settings with a wide range step through a list of round values; the full range stays available in
 * the settings file, and a value typed there that is not in the list is added to it so the slider can show it.
 */
public final class OptionSteps {

	/** The most values a setting may have and still get a button rather than a slider. */
	public static final int MAX_BUTTON_VALUES = 3;

	/** Values the sliders of wide number settings step through. */
	static final Map<String, int[]> STEPS = Map.of(
		"xaeroLodMapRadius", new int[] { 0, 64, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096 },
		"mapFullDetailRadius", new int[] { 0, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256 },
		"statsLogSeconds", new int[] { 0, 10, 30, 60, 120, 300, 600, 1800, 3600 },
		"maxDetailLevel", new int[] { 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }
	);

	private OptionSteps() {}

	/**
	 * The values a setting offers on screen, in order: false and true, a choice's values, or the steps of a number
	 * (with {@code current} added when it is not one of them).
	 */
	public static List<Object> values(Option<?> option, Object current) {
		return values(option, current, Runtime.getRuntime().availableProcessors());
	}

	public static List<Object> values(Option<?> option, Object current, int logicalCpus) {
		List<Object> values = new ArrayList<>();
		if (option instanceof BoolOption) {
			values.add(Boolean.FALSE);
			values.add(Boolean.TRUE);
		}
		else if (option instanceof ChoiceOption<?> choice) {
			values.addAll(choice.values);
		}
		else if (option instanceof IntOption number) {
			TreeSet<Integer> steps = new TreeSet<>();
			int[] listed = STEPS.get(number.key);
			if (listed != null) {
				for (int step : listed) steps.add(step);
			}
			else if (number.key.equals("generatorThreads")) {
				// automatic, then every thread count the machine has
				for (int threads = 0; threads <= Math.max(1, logicalCpus); threads++) steps.add(threads);
			}
			else {
				for (int value = number.min; value <= number.max && steps.size() < 256; value++) steps.add(value);
			}
			steps.removeIf(step -> step < number.min || step > number.max);
			if (current instanceof Integer value && value >= number.min && value <= number.max) steps.add(value);
			if (steps.isEmpty()) steps.add(number.min);
			values.addAll(steps);
		}
		return values;
	}

	/** Whether a setting with these values gets a slider. */
	public static boolean slider(List<?> values) {
		return values.size() > MAX_BUTTON_VALUES;
	}

	/** Where a value sits in the list, or 0 when it is not there. */
	public static int index(List<?> values, Object value) {
		int index = values.indexOf(value);
		return Math.max(0, index);
	}

	/** Slider position (0 to 1) of the value at this index. */
	public static double position(int index, int count) {
		return count <= 1 ? 0.0D : Math.max(0, Math.min(count - 1, index)) / (double)(count - 1);
	}

	/** The index a slider position stands for: the nearest value. */
	public static int indexAt(double position, int count) {
		if (count <= 1 || Double.isNaN(position)) return 0;
		double clamped = Math.max(0.0D, Math.min(1.0D, position));
		return (int)(Math.round(clamped * (count - 1)));
	}

	/**
	 * The index after a slider moved from the value at {@code index} to {@code position}. A mouse sets the position
	 * outright; the arrow keys only nudge it by a pixel, which with few values would round back to where it was, so a
	 * key press always moves at least one value in its direction.
	 */
	public static int moved(int index, double position, int count, boolean key) {
		int next = indexAt(position, count);
		if (key && next == index) {
			double old = position(index, count);
			if (position > old + 1.0e-9D) next = Math.min(count - 1, index + 1);
			else if (position < old - 1.0e-9D) next = Math.max(0, index - 1);
		}
		return next;
	}

	/** The value after this one for a button, wrapping around. */
	public static Object next(List<?> values, Object value) {
		if (values.isEmpty()) return value;
		int index = values.indexOf(value);
		return values.get((index + 1) % values.size());
	}
}
