#!/usr/bin/env bash
# Off-device checks for the Fake VPN packet builders and the plugin refresh
# retry policy.
#
# The tun loop needs Android, but packet framing, checksums, DNS parsing, the
# IP-echo allowlist and the semver/throttle constants are pure Java and can be
# verified on a normal JVM against the stub android.jar.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-/data/data/com.termux/files/usr/share/java/android.jar}"
SHIZUKU_CP="$(printf '%s:' "$ROOT"/libs/shizuku/*.jar)"
OUT="${TMPDIR:-/tmp}/attc-opencode-test"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "missing android.jar: $ANDROID_JAR" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

javac -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR:$SHIZUKU_CP" \
  -sourcepath "$ROOT/app/src" \
  -d "$OUT" \
  "$ROOT/tools/FakeVpnTest.java" \
  "$ROOT/tools/PluginsTest.java" \
  "$ROOT/app/src/com/attc/opencode/FakeVpnStack.java" \
  "$ROOT/app/src/com/attc/opencode/Plugins.java"

java -cp "$OUT:$ANDROID_JAR:$SHIZUKU_CP" FakeVpnTest
java -cp "$OUT:$ANDROID_JAR:$SHIZUKU_CP" PluginsTest

# TerminalBuffer has no Android imports; it shares the classpath so the same
# command works wherever the other suites run.
javac -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR:$SHIZUKU_CP" \
  -d "$OUT" \
  "$ROOT/tools/TerminalBufferTest.java" \
  "$ROOT/app/src/com/attc/opencode/TerminalBuffer.java"

java -cp "$OUT:$ANDROID_JAR:$SHIZUKU_CP" TerminalBufferTest
