#!/usr/bin/env bash
# SEM 0.1.6 ships SimpleEnemyMod(FMLJavaModLoadingContext) only — Forge 47.2.0 on 1.20.1
# constructs mods via no-arg. This adds: public SimpleEnemyMod() { this(FMLJavaModLoadingContext.get()); }
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/libs/simpleenemymod.jar"
ASM=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm" -name 'asm-9*.jar' 2>/dev/null | sort -V | tail -1)
if [[ -z "${ASM}" || ! -f "$JAR" ]]; then
  echo "Need libs/simpleenemymod.jar and an ASM 9.x jar in the Gradle cache" >&2
  exit 1
fi
WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT
cp "$ROOT/scripts/PatchSemCtor.java" "$WORKDIR/"
javac -cp "$ASM" -d "$WORKDIR" "$WORKDIR/PatchSemCtor.java"
java -cp "$WORKDIR:$ASM" PatchSemCtor "$JAR" "$WORKDIR/out.jar"
cp "$WORKDIR/out.jar" "$JAR"
rm -rf "$HOME/.gradle/caches/forge_gradle/deobf_dependencies/blank/simpleenemymod"
echo "Patched $JAR and cleared FG deobf cache. Run ./gradlew compileJava to re-deobf."
