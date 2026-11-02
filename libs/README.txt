Put the jars SeedGenGlobe compiles against here (they are not included in this repository):

  DistantHorizons-3.0.3-b-26.1.2*.jar   (the stock build or the atlasfix build, either works)
  BigGlobe-6.3.0-MC26.1.2.jar           (download it from Big Globe's Modrinth or GitHub page;
                                          Big Globe's license does not allow re-hosting it)
  Vertigo-*.jar                          (optional; a stub of its API is in src/vertigoStubs)

Any file names work: build.gradle takes every *.jar in this folder as compile-only,
and unpacks the library Big Globe nests inside its jar (autocodec).
