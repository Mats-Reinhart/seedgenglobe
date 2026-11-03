#!/usr/bin/env bash
# Builds SeedGenGlobe without Gradle or network access.
# Compiles against the real Distant Horizons, Big Globe, Xaero's World Map / Minimap and Mod Menu jars, the Fabric API
# modules bundled in Distant Horizons (and xaerolib, bundled in Xaero's mods),
# Mojang's brigadier (compiled from source) and generated compile-only stubs for Minecraft classes.
set -euo pipefail

VERSION="${VERSION:-1.3.3+mc26.1.2}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/home/claude/work}"
JAVAC="${JAVAC:-/usr/lib/jvm/java-25-openjdk-amd64/bin/javac}"
JAR="${JAR:-/usr/lib/jvm/java-25-openjdk-amd64/bin/jar}"
export JAVA_TOOL_OPTIONS=

BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/stubs" "$BUILD/classes" "$BUILD/libs"

# 1. compile-only stubs written by hand: the Vertigo API (also used by the Gradle build) and, for this offline
#    build only, the Mixin, ASM, SLF4J and JetBrains annotation classes that Loom would otherwise provide
find "$HERE/stubs" "$HERE/src/vertigoStubs/java" -name '*.java' > "$BUILD/stub-sources.txt"
"$JAVAC" --release 25 -proc:none -nowarn -d "$BUILD/stubs" -cp "$WORK/stubgen/out:$(ls "$WORK"/nested/*.jar | tr '\n' ':')" @"$BUILD/stub-sources.txt"

# 2. classpath: hand stubs, brigadier, real mod jars, Fabric API modules, generated Minecraft stubs (last)
CP="$BUILD/stubs:$WORK/brigadier-classes"
CP="$CP:$WORK/libs/DistantHorizons-3.0.3-b-26.1.2-atlasfix.jar:$WORK/libs/BigGlobe-6.3.0-MC26.1.2.jar"
CP="$CP:$WORK/libs/xaeroworldmap-fabric-26.1.2-1.47.2.jar:$WORK/libs/xaerominimap-fabric-26.1.2-26.6.2.jar:$WORK/libs/modmenu-18.0.2.jar"
for jar in "$WORK"/nested/*.jar; do CP="$CP:$jar"; done
CP="$CP:$WORK/stubgen/out"

find "$HERE/src/main/java" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 25 -proc:none -encoding UTF-8 -g -parameters -Xlint:-options -Xmaxerrs 500 \
	-d "$BUILD/classes" -cp "$CP" @"$BUILD/sources.txt"

# 3. resources
cp -r "$HERE/src/main/resources/." "$BUILD/classes/"
sed -i "s/\${version}/$VERSION/" "$BUILD/classes/fabric.mod.json"
cp "$HERE/LICENSE" "$BUILD/classes/LICENSE_SeedGenGlobe"

# 4. jar (stable entry order and timestamps for reproducible output)
OUT="$BUILD/libs/SeedGenGlobe-$VERSION.jar"
(cd "$BUILD/classes" && find . -type f | sort | sed 's|^\./||' > "$BUILD/jar-entries.txt" && \
	"$JAR" --create --file "$OUT" --date 2026-10-27T00:00:00Z --no-manifest @"$BUILD/jar-entries.txt")
echo "built $OUT"
