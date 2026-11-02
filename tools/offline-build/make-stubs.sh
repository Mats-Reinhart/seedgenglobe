#!/usr/bin/env bash
# Regenerates the compile-only Minecraft and library stubs (see README.md, step 2) into $WORK/stubgen/out.
# Brigadier is left out: build-offline.sh compiles it from its public source instead.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/home/claude/work}"
JAVA="${JAVA:-/usr/lib/jvm/java-25-openjdk-amd64/bin/java}"
JAVAC="${JAVAC:-/usr/lib/jvm/java-25-openjdk-amd64/bin/javac}"
export JAVA_TOOL_OPTIONS=
mkdir -p "$WORK/tools/classes"
"$JAVAC" --release 25 -d "$WORK/tools/classes" "$HERE/StubGen.java" "$HERE/SurfaceScan.java" "$HERE/LinkCheck.java"
OUT="$WORK/stubgen/out"
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$HERE/hierarchy.txt" "$WORK/stubgen/hierarchy.txt"
"$JAVA" -cp "$WORK/tools/classes" StubGen "$OUT" "$HERE/hierarchy.txt" \
	"net/minecraft/,com/mojang/,com/google/,io/netty/,it/unimi/,org/apache/,org/joml/,org/lwjgl/,net/fabricmc/,xaero/pac/" \
	"$WORK"/libs/*.jar
rm -rf "$OUT/com/mojang/brigadier"
echo "stubs in $OUT"
