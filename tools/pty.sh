#!/usr/bin/env bash
# Builds libpty.so for arm64-v8a with the Termux clang.
# The .so only needs libc.so and libdl.so, so it loads in any app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT/app/jni"
OUT="$JNI_DIR/libpty.so"
JNI_INC="${JNI_INC:-/data/data/com.termux/files/usr/include}"

if [ ! -f "$JNI_INC/jni.h" ]; then
  echo "jni.h not found at $JNI_INC" >&2
  exit 1
fi

clang -shared -fPIC -O2 -fvisibility=hidden \
  -I "$JNI_INC" \
  -o "$OUT" "$JNI_DIR/pty.c" -lutil

echo "built $OUT"
readelf -d "$OUT" | grep NEEDED || true
