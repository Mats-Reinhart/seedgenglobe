# Offline build and link check

The released jar was built where Mojang's and Fabric's servers could not be reached, so there was no Minecraft
jar and no Gradle/Loom. These tools made that possible and checked the result. Use Gradle for normal development.

1. `SurfaceScan <outDir> <jar...>` reads the Distant Horizons, Big Globe, Xaero's World Map and Minimap and Mod Menu
   jars (`$WORK/libs`) and other mods built for the same Minecraft version, read only for their references
   (`$WORK/refs`: for 1.3.0 SeedGen, Sodium, Sodium Extra, Lithium, C2ME, Noisium, ScalableLux, ImmediatelyFast and
   Cloth Config), with the jars nested in them, and records every class and member they define, and every member of
   other classes they reference. Those mods were compiled against the real Minecraft 26.1.2, so their references
   describe Minecraft's API.
2. `StubGen <outDir> hierarchy.txt <prefixes> <jar...>` turns the referenced Minecraft and library members into
   compile-only stub classes. `hierarchy.txt` adds the class hierarchy, generic arities and the few members that
   only SeedGenGlobe uses; an interface marked `functional` gets abstract methods so that lambdas compile
   (`Button.OnPress`), every other stub interface method is a default method. `make-stubs.sh` compiles the three tools and regenerates the stubs into
   `$WORK/stubgen/out` with the prefixes the release used (after a change to `hierarchy.txt`, for example).
   Stub methods have erased signatures and constants are not stubbed (javac inlines them), so SeedGenGlobe casts
   what generic Minecraft methods return and spells out a few flag values.
3. `build-offline.sh` (project root) compiles SeedGenGlobe with javac 25 against the real Distant Horizons,
   Big Globe and Xaero's World Map and Minimap jars (Xaero's library comes nested in them), the Fabric API modules
   nested in Distant Horizons, Brigadier built from its public source, the hand-written stubs in `stubs/` (including
   MixinExtras' `@WrapOperation`, which ships with Fabric Loader) and `src/vertigoStubs/`, and the generated stubs.
4. `LinkCheck <mod.jar> <surfaceDir> <jar...>` then checks every class, field and method SeedGenGlobe's bytecode
   uses outside its own code: that it exists in Distant Horizons, Big Globe or Fabric API, or is referenced the
   same way (same owner, descriptor and invoke kind) by those mods' bytecode; that every mixin `@Shadow` and
   `@Inject` target exists in the target jar; and that every MixinExtras `@WrapOperation` target method exists and
   contains the wrapped call (at the given ordinal), with a handler that takes the call's receiver, arguments and an
   `Operation` and returns what the call returns. Mixins into Minecraft classes (which are not available) are
   checked against the methods other mods call: an `@Inject` target nothing else calls is listed as unverified.
   Members nothing else references are listed as unverified for a manual look (for 1.1.0: four Vertigo API members, checked against Vertigo's source, `LevelChunk.getMaxY()`, which
   Minecraft declares on `LevelHeightAccessor`, and the standard Fabric and Mixin entrypoint interfaces).

Paths in the scripts default to the layout of that build machine (`WORK=/home/claude/work`); set `WORK`, `JAVAC`
and `JAR` to use them elsewhere.

For 1.1.0 the result was: 983 references resolved, 8 unverified (above), 0 errors. For 1.2.0, which also reads a
few of Distant Horizons' internal thread pool and config classes for its diagnostics (all of them resolved against
the real Distant Horizons jar): 1396 resolved, the same 8 unverified, 0 errors. For 1.3.0, against Distant Horizons
3.0.3-b with the atlasfix patch and the reference mods above: 1600 resolved, 14 unverified, 0 errors. The unverified
ones are `LevelChunk.getMaxY()` and the three entrypoint interfaces from above, and ten `StructureManager` methods
the structure guard injects into that no other mod calls (all `require = 0`, so a method missing in 26.1.2 only
leaves that one injection out; the one a swamp hut's cat uses, `getStructureWithPieceAt(BlockPos, TagKey)`, is
verified). `GlobeMixinPlugin`'s requirement checks also pass for every Distant Horizons and Xaero mixin against
those jars.

Since 1.3.1 the link check also follows members a class inherits (javac writes `this.getY()` in a subclass of a
Minecraft widget as a reference to the subclass) up to the first class outside the jar that defines the subclass, in
SeedGenGlobe and in the scanned mods alike, so a member SeedGenGlobe's settings list or sliders inherit from Minecraft
is verified when another mod's subclass uses it the same way. For 1.3.1, with the Vertigo jar passed as well: 1709 resolved
(59 of them inherited through SeedGenGlobe's own subclasses), the same 14 unverified as 1.3.0, 0 errors. For 1.3.2,
which also reads Distant Horizons' internal server world, level and repository classes to find its LOD databases and
whether their connections are closed (all resolved against the real Distant Horizons jar): 1813 resolved (60
inherited), the same 14 unverified, 0 errors. `hierarchy.txt` gained `ConfirmScreen`, fastutil's `BooleanConsumer`
(functional) and `IntegratedServer` for it. `tools/SettingsTable.java` prints the README's settings tables from the option list.
