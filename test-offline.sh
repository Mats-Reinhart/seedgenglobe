#!/usr/bin/env bash
# Runs SeedGenGlobe's logic tests without Minecraft.
#  1. ColumnProfileTest: ColumnProfile compiled against small fakes of the Minecraft/Big Globe classes it uses,
#     checked against Distant Horizons' data point rules and Big Globe's light model (plus a 20,000 column fuzz).
#  2. CanopyMathTest: the tree shape helpers of the real compiled mod classes.
# Run build-offline.sh first.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/home/claude/work}"
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk-amd64}"
export JAVA_TOOL_OPTIONS=

OUT="$HERE/build/test"
rm -rf "$OUT"
mkdir -p "$OUT/fakes" "$OUT/real"

"$JDK/bin/javac" --release 25 -nowarn -d "$OUT/fakes" \
	$(find "$HERE/src/testFakes/java" -name '*.java') \
	"$HERE"/stubs/org/jetbrains/annotations/*.java \
	"$HERE/src/main/java/dev/seedgenglobe/terrain/ColumnProfile.java"
"$JDK/bin/java" -ea -cp "$OUT/fakes" dev.seedgenglobe.terrain.ColumnProfileTest

CP="$HERE/build/classes:$HERE/build/stubs:$WORK/libs/DistantHorizons-3.0.3-b-26.1.2-atlasfix.jar:$WORK/libs/BigGlobe-6.3.0-MC26.1.2.jar:$WORK/stubgen/out"
"$JDK/bin/javac" --release 25 -nowarn -d "$OUT/real" -cp "$CP" "$HERE/src/test/java/dev/seedgenglobe/terrain/CanopyMathTest.java"
"$JDK/bin/java" -ea -cp "$OUT/real:$CP" dev.seedgenglobe.terrain.CanopyMathTest
