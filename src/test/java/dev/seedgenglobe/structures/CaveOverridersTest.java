package dev.seedgenglobe.structures;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checks the rule {@link CaveOverriders} leaves Big Globe's cave-only structure overriders out by, against the Big
 * Globe jar in {@code libs/}: its six {@code surface_caves} overriders and their include are exactly the reviewed
 * texts, no other overrider passes, small changes to a script or include make it run again, and in Big Globe's
 * overworld and islands terrain layers cave noise is only read behind a {@code hints.carve} check (the tree walk
 * {@link CaveOverriders} does at runtime with Big Globe's dependency tracking, done here on the layers' text).
 * <p>
 * When this fails after updating Big Globe, review the changed scripts before changing the reviewed texts.
 */
public final class CaveOverridersTest {

	static int checks;

	static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}

	static final String OVERRIDERS = "data/bigglobe/bigglobe/worldgen/overrider/overworld/column_value/";
	static final String LAYERS = "data/bigglobe/bigglobe/worldgen/terrain_layer/";

	public static void main(String[] args) throws IOException {
		// the six reviewed overriders, as Big Globe ships them
		Set<String> seen = new HashSet<>();
		for (String name : new String[] { "campfire", "jigsaw", "lake", "small_house", "square_structures", "windmill_well" }) {
			Script script = overrider(OVERRIDERS + "surface_caves/" + name + ".json");
			check(script.raw && !script.feature, name + ": raw generation only");
			check(CaveOverriders.reviewed(script.source, script.includeIds(), script.includeSources()), name + " is one of the reviewed scripts");
			seen.add(CaveOverriders.withoutWhitespace(script.source));
		}
		check(seen.equals(CaveOverriders.SCRIPTS), "every reviewed script is one of Big Globe's: " + seen.size() + " of " + CaveOverriders.SCRIPTS.size());

		// overriders that shape the surface or change more than cave noise run as before
		for (String path : new String[] {
			"surface_y/jigsaw.json", "surface_y/small_house.json", "surface_y/campfire.json", "surface_y/ocean_monument.json",
			"0/lake.json", "obelisk_raw.json", "obelisk_features.json", "chasm_exclusion.json", "dungeon.json", "underground_pocket.json"
		}) {
			Script script = overrider(OVERRIDERS + path);
			check(!CaveOverriders.reviewed(script.source, script.includeIds(), script.includeSources()), path + " is not left out");
		}

		// the rule itself: whitespace does not matter, anything else does
		Script lake = overrider(OVERRIDERS + "surface_caves/lake.json");
		String[] ids = lake.includeIds(), sources = lake.includeSources();
		check(CaveOverriders.reviewed(lake.source.replace(" ", "\n\t  "), ids, sources), "reformatted script still reviewed");
		check(CaveOverriders.reviewed(lake.source, ids, new String[] { sources[0].replace("\n", "\r\n") }), "CRLF include still reviewed");
		check(!CaveOverriders.reviewed(lake.source.replace("0.0625L", "0.125L"), ids, sources), "a changed number runs again");
		check(!CaveOverriders.reviewed(lake.source + "\n;comment", ids, sources), "an added comment runs again");
		check(!CaveOverriders.reviewed(lake.source, ids, new String[] { sources[0].replace("16.0L", "32.0L") }), "a changed include runs again");
		check(!CaveOverriders.reviewed(lake.source, ids, new String[] { sources[0] + "\nworld_traits.`bigglobe:exact_surface_y` = 0.0L" }), "an include that writes more runs again");
		check(!CaveOverriders.reviewed(lake.source, new String[] { "mypack:include/cave_exclude_surface" }, sources), "the same text under another id runs again");
		check(!CaveOverriders.reviewed(lake.source, new String[0], new String[0]), "no include runs again");
		check(!CaveOverriders.reviewed(lake.source, new String[] { ids[0], ids[0] }, new String[] { sources[0], sources[0] }), "two includes run again");
		check(!CaveOverriders.reviewed(null, ids, sources), "no script runs again");
		check(!CaveOverriders.reviewed(lake.source, ids, new String[] { null }), "an include without text runs again");

		// the hints.carve check of terrain layers
		check(CaveOverriders.carveGate("hints.carve") && CaveOverriders.carveGate(" hints . carve\n"), "hints.carve gates");
		check(!CaveOverriders.carveGate("!hints.carve") && !CaveOverriders.carveGate("hints.carve || true") && !CaveOverriders.carveGate("hints.fill") && !CaveOverriders.carveGate(null), "nothing else gates");

		// Big Globe's terrain layers read cave noise only behind hints.carve
		for (String root : new String[] { "overworld/root", "islands/root" }) {
			Walk walk = new Walk();
			walk(root, false, 0, walk);
			check(walk.ungated.isEmpty(), root + ": layers that run without carving and read cave noise: " + walk.ungated);
			check(walk.gated.contains("overworld/cave_carver"), root + ": the cave carver reads cave noise behind hints.carve: " + walk.gated);
		}

		System.out.println("CaveOverridersTest: " + checks + " checks passed");
	}

	/** An overrider's script as Big Globe decodes it: its source lines joined, and its includes. */
	record Script(String source, List<String> includes, boolean raw, boolean feature) {

		String[] includeIds() {
			return this.includes.toArray(new String[0]);
		}

		String[] includeSources() {
			String[] sources = new String[this.includes.size()];
			for (int index = 0; index < sources.length; index++) sources[index] = include(this.includes.get(index));
			return sources;
		}
	}

	static Script overrider(String path) throws IOException {
		JsonObject json = JsonParser.parseString(resource(path)).getAsJsonObject();
		JsonElement script = json.get("script");
		List<String> includes = new ArrayList<>();
		String source;
		if (script.isJsonObject()) {
			source = lines(script.getAsJsonObject().get("source"));
			JsonElement list = script.getAsJsonObject().get("includes");
			if (list != null) {
				if (list.isJsonArray()) for (JsonElement id : list.getAsJsonArray()) includes.add(id.getAsString());
				else includes.add(list.getAsString());
			}
		}
		else {
			source = lines(script);
		}
		boolean raw = !json.has("raw_generation") || json.get("raw_generation").getAsBoolean();
		boolean feature = !json.has("feature_generation") || json.get("feature_generation").getAsBoolean();
		return new Script(source, includes, raw, feature);
	}

	/** Big Globe's multi-line strings: one string, or a list of lines. */
	static String lines(JsonElement element) {
		if (!element.isJsonArray()) return element.getAsString();
		StringBuilder text = new StringBuilder();
		for (JsonElement line : element.getAsJsonArray()) {
			if (text.length() > 0) text.append('\n');
			text.append(line.getAsString());
		}
		return text.toString();
	}

	/** An include's text, where Big Globe's script file resolver finds it. */
	static String include(String id) {
		int colon = id.indexOf(':');
		try {
			return resource("data/" + id.substring(0, colon) + "/bigglobe/script_file/" + id.substring(colon + 1) + ".gs");
		}
		catch (IOException missing) {
			throw new AssertionError("missing include " + id, missing);
		}
	}

	static String resource(String path) throws IOException {
		try (InputStream stream = CaveOverridersTest.class.getClassLoader().getResourceAsStream(path)) {
			if (stream == null) throw new IOException("not on the classpath (is Big Globe's jar in libs/?): " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	static final class Walk {
		final Set<String> gated = new HashSet<>();
		final List<String> ungated = new ArrayList<>();
	}

	/** Like {@link CaveOverriders}' walk: a layer's own scripts and includes, then its children. */
	static void walk(String layer, boolean gated, int depth, Walk walk) throws IOException {
		check(depth < 64, "layer tree too deep at " + layer);
		JsonObject json = JsonParser.parseString(resource(LAYERS + layer + ".json")).getAsJsonObject();
		JsonObject valid = json.has("valid") ? json.getAsJsonObject("valid") : null;
		if (!gated && valid != null && valid.has("where")) gated = CaveOverriders.carveGate(lines(valid.get("where")));
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			if (!entry.getKey().equals("children")) text.append(entry.getValue()).append('\n');
		}
		for (String include : includes(json)) text.append(include(include)).append('\n');
		boolean reads = false;
		for (String trait : CaveOverriders.CAVE_TRAITS) reads |= text.indexOf(trait) >= 0;
		if (reads) {
			if (gated) walk.gated.add(layer);
			else walk.ungated.add(layer);
		}
		if (json.has("children")) {
			for (JsonElement child : json.getAsJsonArray("children")) {
				String id = child.getAsString();
				check(id.startsWith("bigglobe:"), "child layer in Big Globe's namespace: " + id);
				walk(id.substring("bigglobe:".length()), gated, depth + 1, walk);
			}
		}
	}

	/** Every include named anywhere in a layer's JSON. */
	static List<String> includes(JsonElement element) {
		List<String> found = new ArrayList<>();
		if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				if (entry.getKey().equals("includes")) {
					JsonElement list = entry.getValue();
					if (list.isJsonArray()) for (JsonElement id : (JsonArray)(list)) found.add(id.getAsString());
					else found.add(list.getAsString());
				}
				else {
					found.addAll(includes(entry.getValue()));
				}
			}
		}
		else if (element.isJsonArray()) {
			for (JsonElement item : element.getAsJsonArray()) found.addAll(includes(item));
		}
		return found;
	}
}
