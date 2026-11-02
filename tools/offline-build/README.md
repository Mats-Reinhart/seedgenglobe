# Offline build and link check

The released jar was built where Mojang's and Fabric's servers could not be reached, so there was no Minecraft
jar and no Gradle/Loom. These tools made that possible and checked the result. Use Gradle for normal development.

1. `SurfaceScan <outDir> <jar...>` reads the Distant Horizons, Big Globe and SeedGen jars (and the jars nested in
   them) and records every class and member they define, and every member of other classes they reference.
   Those mods were compiled against the real Minecraft 26.1.2, so their references describe Minecraft's API.
2. `StubGen <outDir> hierarchy.txt <prefixes> <jar...>` turns the referenced Minecraft and library members into
   compile-only stub classes. `hierarchy.txt` adds the class hierarchy, generic arities and the few members that
   only SeedGenGlobe uses.
3. `build-offline.sh` (project root) compiles SeedGenGlobe with javac 25 against the real Distant Horizons and
   Big Globe jars, the Fabric API modules nested in Distant Horizons, Brigadier built from its public source, the
   hand-written stubs in `stubs/` and `src/vertigoStubs/`, and the generated stubs.
4. `LinkCheck <mod.jar> <surfaceDir> <jar...>` then checks every class, field and method SeedGenGlobe's bytecode
   uses outside its own code: that it exists in Distant Horizons, Big Globe or Fabric API, or is referenced the
   same way (same owner, descriptor and invoke kind) by those mods' bytecode; and that every mixin `@Shadow`
   and `@Inject` target exists in the Distant Horizons jar. Members nothing else references are listed as
   unverified for a manual look (for 1.0.0: three Vertigo API members, checked against Vertigo's source, and the
   standard Fabric and Mixin entrypoint interfaces).

Paths in the scripts default to the layout of that build machine (`WORK=/home/claude/work`); set `WORK`, `JAVAC`
and `JAR` to use them elsewhere.
