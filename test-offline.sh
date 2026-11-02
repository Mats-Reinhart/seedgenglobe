#!/usr/bin/env bash
# Runs SeedGenGlobe's logic tests without Minecraft.
#  1. ColumnProfileTest: ColumnProfile compiled against small fakes of the Minecraft/Big Globe classes it uses,
#     checked against Distant Horizons' data point rules and Big Globe's light model (plus a 20,000 column fuzz).
#  2. CanopyMathTest: the tree shape helpers of the real compiled mod classes.
#  3. GroundCoverTest: the ground cover decorators and Distant Horizons' 2 x 2 merge, on the real compiled classes.
#  4. XaeroPixelTest: reading a column the way Xaero's World Map reads a chunk column, against the same fakes.
#  5. LodMapGeometryTest: the region rings, keys, distances, detail levels and tile rules of the Xaero LOD map.
#  6. CaptureViewTest: the sample points of a LOD section and the section structures are placed into (reads, kept
#     writes, heightmaps, relit columns), against the same fakes as ColumnProfileTest.
#  7. LodCanvasTest: how LOD sections of every detail level are merged into map regions (finest data wins, splitting
#     cells, snapshots, per-tile detail, coverage), against a block-by-block model, on the same fakes.
#  8. SchedulerTest: the section scheduler (nearest first, following the player, weights, aging, cancelling, stopping
#     part-way), the CPU and memory presets and the progress counters, on the real compiled classes.
#  9. ConfigTest: the settings file (round trip of every option, broken values, clamping, 1.2.0 files).
# Run build-offline.sh first.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/home/claude/work}"
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk-amd64}"
export JAVA_TOOL_OPTIONS=

OUT="$HERE/build/test"
rm -rf "$OUT"
mkdir -p "$OUT/fakes" "$OUT/real"

"$JDK/bin/javac" --release 25 -proc:none -nowarn -d "$OUT/fakes" \
	$(find "$HERE/src/testFakes/java" -name '*.java') \
	"$HERE"/stubs/org/jetbrains/annotations/*.java \
	"$HERE/src/main/java/dev/seedgenglobe/terrain/ColumnProfile.java" \
	"$HERE/src/main/java/dev/seedgenglobe/terrain/SampledColumns.java" \
	"$HERE/src/main/java/dev/seedgenglobe/structures/SampleGrid.java" \
	"$HERE/src/main/java/dev/seedgenglobe/structures/CaptureView.java" \
	"$HERE/src/main/java/dev/seedgenglobe/xaero/MapPixel.java" \
	"$HERE/src/main/java/dev/seedgenglobe/xaero/XaeroPixel.java" \
	"$HERE/src/main/java/dev/seedgenglobe/xaero/MapSection.java" \
	"$HERE/src/main/java/dev/seedgenglobe/xaero/LodRegion.java" \
	"$HERE/src/main/java/dev/seedgenglobe/xaero/RegionCanvas.java"
"$JDK/bin/java" -ea -cp "$OUT/fakes" dev.seedgenglobe.terrain.ColumnProfileTest
"$JDK/bin/java" -ea -cp "$OUT/fakes" dev.seedgenglobe.xaero.XaeroPixelTest
"$JDK/bin/java" -ea -cp "$OUT/fakes" dev.seedgenglobe.structures.CaptureViewTest
"$JDK/bin/java" -ea -cp "$OUT/fakes" dev.seedgenglobe.xaero.LodCanvasTest

# Distant Horizons is left off the classpath: none of the tests needs its classes, so none of its code runs here
CP="$HERE/build/classes:$HERE/build/stubs:$WORK/libs/BigGlobe-6.3.0-MC26.1.2.jar"
CP="$CP:$WORK/libs/xaeroworldmap-fabric-26.1.2-1.47.2.jar:$WORK/libs/xaerominimap-fabric-26.1.2-26.6.2.jar:$WORK/libs/modmenu-18.0.2.jar"
for jar in "$WORK"/nested/*.jar; do CP="$CP:$jar"; done
CP="$CP:$WORK/stubgen/out"
# a logger that prints, ahead of the compile-only SLF4J stub, for code that logs while it is tested
mkdir -p "$OUT/support"
"$JDK/bin/javac" --release 25 -proc:none -nowarn -d "$OUT/support" -cp "$CP" $(find "$HERE/src/testSupport/java" -name '*.java')
"$JDK/bin/javac" --release 25 -proc:none -nowarn -d "$OUT/real" -cp "$CP" \
	"$HERE/src/test/java/dev/seedgenglobe/terrain/CanopyMathTest.java" \
	"$HERE/src/test/java/dev/seedgenglobe/terrain/GroundCoverTest.java" \
	"$HERE/src/test/java/dev/seedgenglobe/xaero/LodMapGeometryTest.java" \
	"$HERE/src/test/java/dev/seedgenglobe/gen/SchedulerTest.java" \
	"$HERE/src/test/java/dev/seedgenglobe/config/ConfigTest.java"
RUN="$OUT/support:$OUT/real:$CP"
"$JDK/bin/java" -ea -cp "$RUN" dev.seedgenglobe.terrain.CanopyMathTest
"$JDK/bin/java" -ea -cp "$RUN" dev.seedgenglobe.terrain.GroundCoverTest
"$JDK/bin/java" -ea -cp "$RUN" dev.seedgenglobe.xaero.LodMapGeometryTest
"$JDK/bin/java" -ea -Dseedgenglobe.test.quiet=true -cp "$RUN" dev.seedgenglobe.gen.SchedulerTest
"$JDK/bin/java" -ea -cp "$RUN" dev.seedgenglobe.config.ConfigTest
