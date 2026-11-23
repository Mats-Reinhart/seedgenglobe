Put the jars SeedGenGlobe compiles against here (they are not included in this repository):

  DistantHorizons-3.0.3-b-26.1.2*.jar   (the stock build or the atlasfix build, either works)
  Big Globe-6.4.0-MC26.1.2.jar          (download it from Big Globe's Modrinth or GitHub page;
                                          Big Globe's license does not allow re-hosting it; 6.3.0 works too)
  xaeroworldmap-fabric-26.1.2-1.47.2.jar (Xaero's World Map; needed to compile the Xaero integration)
  xaerominimap-fabric-26.1.2-26.6.2.jar  (Xaero's Minimap)
  Vertigo-*.jar                          (optional; a stub of its API is in src/vertigoStubs)

Only one Big Globe jar at a time: the check task's CaveOverridersTest reads Big Globe's overrider and terrain layer
files from it.

Any file names work: build.gradle takes every *.jar in this folder (not in subfolders) as compile-only,
and unpacks the libraries nested inside them (Big Globe's autocodec, Xaero's xaerolib).
Xaero's mods are All Rights Reserved: do not re-host them either.
