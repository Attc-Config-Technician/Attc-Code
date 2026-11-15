#!/usr/bin/env bash
# Reproduces the F-Droid build locally or on the F-Droid build server.
#
# Usage:
#   bash tools/build-fdroid.sh [versionName] [versionCode] [sdkPath] [ndkPath]
#
# The script:
#   1. locates android.jar and the SDK build-tools,
#   2. compiles the native runtime from native/ with the NDK,
#   3. assembles an unsigned APK with tools/build.sh (SIGN=0).
#
# fdroidserver then signs the resulting APK with the F-Droid key.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

VERSION_NAME="${1:-${VERSION_NAME:-}}"
VERSION_CODE="${2:-${VERSION_CODE:-}}"
SDK_DIR="${3:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
NDK_DIR="${4:-${NDK:-${ANDROID_NDK_HOME:-}}}"

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "usage: $0 <versionName> <versionCode> [sdkPath] [ndkPath]" >&2
  exit 1
fi
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR" ]; then
  echo "Android SDK not found; pass it as the third argument or set ANDROID_HOME" >&2
  exit 1
fi
if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
  echo "Android NDK not found; pass it as the fourth argument or set ANDROID_NDK_HOME" >&2
  exit 1
fi

ANDROID_JAR=""
# foregroundServiceType needs API 29+, and the F-Droid buildserver ships
# platforms up to 33. Prefer a modern platform while keeping targetSdk at 28.
for level in 33 34 35 36 32 31 30 29; do
  if [ -f "$SDK_DIR/platforms/android-$level/android.jar" ]; then
    ANDROID_JAR="$SDK_DIR/platforms/android-$level/android.jar"
    break
  fi
done
if [ -z "$ANDROID_JAR" ]; then
  ANDROID_JAR="$(find "$SDK_DIR/platforms" -maxdepth 2 -name android.jar 2>/dev/null \
    | sort -V | tail -1)"
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "no android.jar under $SDK_DIR/platforms" >&2
  exit 1
fi

BUILD_TOOLS="$SDK_DIR/build-tools/34.0.0"
if [ ! -d "$BUILD_TOOLS" ]; then
  BUILD_TOOLS="$(find "$SDK_DIR/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null \
    | sort -V | tail -1)"
fi
if [ ! -x "$BUILD_TOOLS/aapt2" ] || [ ! -x "$BUILD_TOOLS/d8" ]; then
  echo "no usable build-tools under $SDK_DIR/build-tools" >&2
  exit 1
fi

export ANDROID_JAR
export PATH="$BUILD_TOOLS:$PATH"
export NDK="$NDK_DIR"
export NATIVE_OUT="${NATIVE_OUT:-$ROOT/build-native}"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"

echo "android.jar: $ANDROID_JAR"
echo "build-tools: $BUILD_TOOLS"
echo "ndk:         $NDK_DIR"

NATIVE_OUT="$NATIVE_OUT" bash "$ROOT/tools/build-native.sh"

VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" \
  NAME="ATTC-CODE-$VERSION_NAME" \
  SIGN=0 SKIP_ICON=1 \
  NATIVE_OUT="$NATIVE_OUT" OUT_DIR="$OUT_DIR" DELIVER_DIR= \
  bash "$ROOT/tools/build.sh"

echo "built $OUT_DIR/ATTC-CODE-$VERSION_NAME.apk"
sha256sum "$OUT_DIR/ATTC-CODE-$VERSION_NAME.apk"
