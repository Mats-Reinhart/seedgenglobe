# Changelog

## 1.1.0

### Xaero's World Map and Minimap
- New: the surface of the whole Distant Horizons radius on Xaero's World Map and Minimap (`xaeroLodMap`,
  `xaeroLodMapRadius`). Computed from Big Globe's terrain scripts on a background thread, one map pixel per block
  near you and coarser with distance like LODs, filled nearest first. Real chunks always replace it as soon as
  Xaero maps them. Multiplayer maps keep it in Xaero's saves; singleplayer maps get it back from Xaero's texture
  cache in later sessions.
- LOD map pixels record Big Globe's surface biome, so the map's biome readout names the biome on the ground.
- With Vertigo, Xaero no longer maps chunks whose surface was not synced yet as black, empty chunks at y = -1024
  with the plains biome (the wrong biome readout in explored areas). Such chunks already in a saved map are covered
  by LOD terrain until Xaero maps them properly.

### Distant Horizons LODs
- Fixed: black holes in the LODs while Distant Horizons is still building them. Generated sections no longer write
  partial data into their coarser parent sections, which made DH hide finer sections that were already there
  (`updateParentSections` restores DH's default).
- New: ground cover on generated LODs (`groundCover`): short grass, ferns, dead bushes, flower patches (laid out like
  Big Globe's flower controller), cherry petals, leaf litter, bushes, sticks and rocks with Big Globe's chances.
  Distant Horizons colours a block by the plants on it, so generated LODs now match LODs of explored chunks instead
  of showing a visible square around the spawn chunks.

### Other
- The mixin plugin now checks Xaero's classes before patching them, and the offline link check verifies every
  `@WrapOperation` target and handler.
- More offline tests: ground cover chances and DH's 2 x 2 merge, how a column reads on Xaero's map, and the LOD map's
  region bookkeeping.

## 1.0.0
- First release: seed-based Distant Horizons generator for Big Globe, multiplayer client generation, the Vertigo
  guard, Big Globe colours for LODs, approximate tree canopies.
