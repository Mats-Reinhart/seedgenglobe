package dev.seedgenglobe.structures;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;

import builderb0y.bigglobe.chunkgen.BigGlobeScriptedChunkGenerator;
import builderb0y.bigglobe.chunkgen.scripted.TerrainLayer;
import builderb0y.bigglobe.columns.ColumnScript.ColumnToBooleanScript;
import builderb0y.bigglobe.columns.dependencies.DependencyView;
import builderb0y.bigglobe.columns.traits.WorldTrait;
import builderb0y.bigglobe.overriders.ColumnValueOverrider;
import builderb0y.bigglobe.util.UnregisteredObjectException;
import builderb0y.scripting.parsing.input.ScriptFileResolver.ResolvedInclude;
import builderb0y.scripting.parsing.input.ScriptFileResolver.ResolvedIncludes;
import builderb0y.scripting.parsing.input.ScriptUsage;

/**
 * Recognises Big Globe's structure overriders that only keep caves away from structures, which LOD terrain does not
 * need while its underground is not carved.
 * <p>
 * Near villages, houses, campfires, lakes, wells and windmills Big Globe runs two kinds of raw column value overriders:
 * the ones that shape the surface (the flattened ground under a village, a lake's basin) and its {@code surface_caves}
 * ones, which raise the cave noise of the 16 blocks under the surface so that no cave opens beneath the structure.
 * Cave noise is a 3D value, and before an overrider may change it Big Globe computes it for the whole column. In the
 * 1.4.0 test on Big Globe 6.4.0 that made "structure terrain" a third of all generation time (1.2 ms for every four
 * columns near a structure), although LODs that are not carved never read cave noise: Big Globe's cave carver, the
 * only terrain layer that does, runs only where its hints say carve (Big Globe's "Carve" and "Decorate" underground
 * modes, not SeedGenGlobe's default Fill).
 * <p>
 * An overrider is only left out when it is exactly one of the scripts reviewed here (whitespace aside): a script that
 * measures distances to structure pieces and calls {@code excludeSurface}, with Big Globe's
 * {@code cave_exclude_surface} as its only include, which reads the surface height and adds to the cave noise and
 * writes nothing else. They are the same in Big Globe 6.3.0, 6.4.0 and its Minecraft 26.2 build. Any other text, a data
 * pack's version or a later Big Globe's, runs as before. So does everything when the terrain layers are not as
 * reviewed: a layer that runs without carving must not read the cave traits, and the cave carver must be seen reading
 * them behind its {@code hints.carve} check (which also shows that Big Globe's dependency tracking can be trusted).
 * <p>
 * What the left-out overriders would have computed beforehand is computed when it is first needed instead, as 1.4.0
 * already does for the overriders of structures further away. Here that is Big Globe's cave type, which the Fill
 * underground reads only for the rock layers near the deepslate boundary, hundreds of blocks below the surface.
 * {@code fullStructurePrecompute} keeps Big Globe's exact order and every overrider.
 */
final class CaveOverriders {

	static final String INCLUDE_ID = "bigglobe:include/cave_exclude_surface";

	/** Big Globe's {@code include/cave_exclude_surface.gs} without whitespace. */
	static final String INCLUDE = "voidexcludeSurface(doubleamount:if(amount>0.0L:doublesurfaceY=world_traits.`bigglobe:exact_surface_y`"
		+ "for(intyinrange(floorInt(surfaceY-16.0L),ceilInt(surfaceY)):doubleamountForY=unmixLinear(surfaceY-16.0L,surfaceY,y)"
		+ "world_traits.`bigglobe:cave_noise`(y)+=float(amountForY^2*amount))))";

	/** The scripts of Big Globe's {@code overworld/column_value/surface_caves} overriders without whitespace. */
	static final Set<String> SCRIPTS = Set.of(
		// campfire
		"for(StructureStartstartinstructures:intlimit=min(2,start.pieces.size)for(intindex=0,index<limit,++index:"
			+ "StructurePiecepiece=start.pieces.(index)doubledistance=distanceToCircle(piece,4.0L)"
			+ "doublemixLevel=smooth(1.0L-distance*0.125L)excludeSurface(mixLevel)))",
		// jigsaw
		"for(StructureStartstartinstructures:if(start.structure.generationStep=='surface_structures':"
			+ "for(StructurePiecepieceinstart.pieces:intdiameter=max(piece.sizeX,piece.sizeZ)+1doubleradius=diameter*0.5L"
			+ "doubledistance=distanceToCircle(piece,radius)doublemixLevel=smooth(1.0-distance/min(radius,16.0L))excludeSurface(mixLevel))))",
		// lake
		"for(StructureStartstartinstructures:doubledistance=distanceToCircle(start)doublemixLevel=smooth(1.0L-distance*0.0625L)excludeSurface(mixLevel))",
		// small_house
		"for(StructureStartstartinstructures:StructurePiecepiece=start.pieces.(0)doubledistance=distanceToCircle(piece,4.0L)"
			+ "doublemixLevel=smooth(1.0L-distance*0.0625L)excludeSurface(mixLevel)if(start.pieces.size>1:piece=start.pieces.(1)"
			+ "distance=distanceToSquare(piece)mixLevel=smooth(1.0L-distance*0.0625L)excludeSurface(mixLevel)))",
		// square_structures
		"for(StructureStartstartinstructures:doubledistance=distanceToSquare(start)doublemixLevel=smooth(1.0L-distance*0.0625L)excludeSurface(mixLevel))",
		// windmill_well
		"for(StructureStartstartinstructures:doubledistance=distanceToCircle(start,4.0L)excludeSurface(smooth(1.0L-distance*0.125L)))"
	);

	/** The world traits Big Globe's cave noise is read through: directly, and through the cave system's getNoise. */
	static final Set<String> CAVE_TRAITS = Set.of("bigglobe:cave_noise", "bigglobe:cave_system");

	/** Deeper than any real layer tree: past this the tree is not trusted. */
	private static final int MAX_DEPTH = 64;

	private CaveOverriders() {}

	/**
	 * Which raw overriders LODs may leave out while not carving.
	 *
	 * @param caveOnly one flag per raw overrider, all false when none may be left out
	 * @param count how many flags are set
	 * @param note what was decided, for the log; empty when there was nothing to decide (no such overriders)
	 */
	record Result(boolean[] caveOnly, int count, String note) {

		static Result none(int overriders, String note) {
			return new Result(new boolean[overriders], 0, note);
		}
	}

	static String withoutWhitespace(String text) {
		StringBuilder builder = new StringBuilder(text.length());
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (!Character.isWhitespace(character)) builder.append(character);
		}
		return builder.toString();
	}

	/**
	 * Whether a script with these includes is one of the reviewed cave-only overriders.
	 *
	 * @param source the overrider's own script, without its includes
	 * @param includeIds the identifiers of its includes, in order
	 * @param includeSources their texts
	 */
	static boolean reviewed(@Nullable String source, String[] includeIds, String[] includeSources) {
		if (source == null || includeIds.length != 1 || includeSources.length != 1) return false;
		if (!INCLUDE_ID.equals(includeIds[0]) || includeSources[0] == null) return false;
		return INCLUDE.equals(withoutWhitespace(includeSources[0])) && SCRIPTS.contains(withoutWhitespace(source));
	}

	/** Whether a {@code valid.where} script is exactly {@code hints.carve}: the layer and everything under it only run when carving. */
	static boolean carveGate(@Nullable String where) {
		return where != null && withoutWhitespace(where).equals("hints.carve");
	}

	/** Marks the raw overriders (in Big Globe's order) that only keep caves away from structures. */
	static Result find(BigGlobeScriptedChunkGenerator generator, Holder<ColumnValueOverrider.Entry>[] overriders) {
		try {
			boolean[] caveOnly = new boolean[overriders.length];
			List<String> names = new ArrayList<>();
			for (int index = 0; index < overriders.length; index++) {
				if (reviewed((ColumnValueOverrider.Entry)(overriders[index].value()))) {
					caveOnly[index] = true;
					names.add(name(overriders[index]));
				}
			}
			if (names.isEmpty()) return Result.none(overriders.length, "");
			Walk walk = new Walk();
			walk(generator.layer, false, 0, walk);
			if (walk.ungated != null) {
				return Result.none(overriders.length, "runs all of them: terrain layer " + walk.ungated + " reads cave noise without carving");
			}
			if (!walk.gated) {
				return Result.none(overriders.length, "runs all of them: no terrain layer was seen reading cave noise behind Big Globe's hints.carve check");
			}
			return new Result(caveOnly, names.size(), "leaves out " + String.join(", ", names) + " while LODs are not carved");
		}
		catch (RuntimeException | StackOverflowError | LinkageError error) {
			// a Big Globe whose scripts cannot be read this way: every overrider runs, as before 1.5.0
			return Result.none(overriders.length, "runs all of them: " + error);
		}
	}

	private static boolean reviewed(ColumnValueOverrider.Entry entry) {
		// Big Globe itself keeps these out of feature generation: nothing placed on the surface needs them
		if (!entry.raw_generation || entry.feature_generation || entry.script == null) return false;
		ScriptUsage usage = entry.script.usage;
		if (!plainSource(usage)) return false;
		ResolvedIncludes includes = usage.includes;
		if (includes == null) return false;
		Holder<ResolvedInclude>[] list = includes.includes();
		String[] ids = new String[list.length], sources = new String[list.length];
		for (int include = 0; include < list.length; include++) {
			ResolvedInclude resolved = (ResolvedInclude)(list[include].value());
			ids[include] = String.valueOf(resolved.id());
			sources[include] = resolved.source();
		}
		return reviewed(usage.getRawSource(), ids, sources);
	}

	/** A script written out in its JSON, not a template or a file (whose text could be anything). */
	private static boolean plainSource(@Nullable ScriptUsage usage) {
		return usage != null && usage.getTemplate() == null && usage.getFile() == null;
	}

	private static final class Walk {
		/** A layer behind a hints.carve check reads the cave traits, as Big Globe's cave carver does. */
		boolean gated;
		/** The first layer that runs without carving and reads them. */
		@Nullable String ungated;
	}

	private static void walk(Holder<TerrainLayer> holder, boolean gated, int depth, Walk walk) {
		if (depth > MAX_DEPTH) {
			walk.ungated = "tree deeper than " + MAX_DEPTH + " layers";
			return;
		}
		TerrainLayer layer = (TerrainLayer)(holder.value());
		if (!gated && layer.valid != null && layer.valid.where() != null) {
			ColumnToBooleanScript.Catcher where = layer.valid.where();
			gated = plainSource(where.usage) && where.usage.includes == null && carveGate(where.usage.getRawSource());
		}
		// its own scripts: state, condition, script, valid, before and after children (children are layers, not traits)
		if (layer.streamDirectDependencies().anyMatch(CaveOverriders::caveTrait)) {
			if (gated) walk.gated = true;
			else if (walk.ungated == null) walk.ungated = name(holder);
		}
		for (Holder<TerrainLayer> child : layer.children) {
			walk(child, gated, depth + 1, walk);
			if (walk.ungated != null) return;
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static boolean caveTrait(Holder<? extends DependencyView> holder) {
		if (!(holder.value() instanceof WorldTrait)) return false;
		try {
			return CAVE_TRAITS.contains(String.valueOf(UnregisteredObjectException.getID((Holder)(holder))));
		}
		catch (RuntimeException unregistered) {
			return false;
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static String name(Holder<?> holder) {
		try {
			return String.valueOf(UnregisteredObjectException.getID((Holder)(holder)));
		}
		catch (RuntimeException unregistered) {
			return "(unnamed)";
		}
	}
}
