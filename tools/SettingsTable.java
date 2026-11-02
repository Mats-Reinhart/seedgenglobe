import java.util.Locale;

import dev.seedgenglobe.GlobeConfig;

/**
 * Prints the README's settings tables (one per category) from {@link GlobeConfig#OPTIONS}, so the README says what
 * the config file and the in-game tooltips say. Run against the built classes:
 * {@code java -cp build/classes:<stubs> tools/SettingsTable.java}.
 */
public class SettingsTable {

	public static void main(String[] args) {
		GlobeConfig defaults = new GlobeConfig();
		GlobeConfig.Category category = null;
		StringBuilder out = new StringBuilder();
		for (GlobeConfig.Option<?> option : GlobeConfig.OPTIONS) {
			if (option.category != category) {
				category = option.category;
				if (out.length() > 0) out.append('\n');
				out.append("**").append(category.label).append("**\n\n| Key | In game | Default | Meaning |\n|---|---|---|---|\n");
			}
			StringBuilder meaning = new StringBuilder(option.description);
			if (option instanceof GlobeConfig.ChoiceOption<?> choice) {
				meaning.append(" (");
				for (int i = 0; i < choice.values.size(); i++) {
					if (i > 0) meaning.append(", ");
					meaning.append('`').append(choice.values.get(i).id()).append('`');
				}
				meaning.append(')');
			}
			else if (option instanceof GlobeConfig.IntOption range) {
				meaning.append(" (").append(range.min).append('-').append(range.max);
				if (range.zeroLabel != null && range.min <= 0) meaning.append(", 0 = ").append(range.zeroLabel.toLowerCase(Locale.ROOT));
				meaning.append(')');
			}
			if (!option.apply.note.isEmpty()) meaning.append(" *").append(option.apply.note).append('*');
			out.append("| `").append(option.key).append("` | ").append(option.key.equals("generatorPriority") ? "(file only)" : option.label).append(" | `").append(value(option, defaults)).append("` | ").append(meaning).append(" |\n");
		}
		System.out.print(out);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static String value(GlobeConfig.Option option, GlobeConfig config) {
		Object value = option.get(config);
		return value instanceof GlobeConfig.Choice choice ? choice.id() : String.valueOf(value);
	}
}
