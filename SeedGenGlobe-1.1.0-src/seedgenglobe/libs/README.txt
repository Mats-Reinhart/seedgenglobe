Put the jars SeedGenGlobe compiles against here (they are not included in this repository):

  DistantHorizons-3.0.3-b-26.1.2*.jar   (the stock build or the atlasfix build, either works)
  BigGlobe-6.3.0-MC26.1.2.jar           (download it from Big Globe's Modrinth or GitHub page;
                                          Big Globe's license does not allow re-hosting it)
  xaeroworldmap-fabric-26.1.2-1.47.2.jar (Xaero's World Map; needed to compile the Xaero integration)
  xaerominimap-fabric-26.1.2-26.6.2.jar  (Xaero's Minimap)
  Vertigo-*.jar                          (optional; a stub of its API is in src/vertigoStubs)

Any file names work: build.gradle takes every *.jar in this folder as compile-only,
and unpacks the libraries nested inside them (Big Globe's autocodec, Xaero's xaerolib).
Xaero's mods are All Rights Reserved: do not re-host them either.
