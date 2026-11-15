#!/usr/bin/env bash
# Builds a signed ATTC OPENCODE APK without Gradle.
#
# Local (Termux):
#   bash tools/build.sh
#
# Environment overrides:
#   OUT_DIR, ANDROID_JAR, FRAMEWORK, VERSION_CODE, VERSION_NAME, NAME,
#   KS_FILE, KS_PASS, KS_ALIAS, MIN_SDK, TARGET_SDK, JNI_INC, TERMUX_PREFIX
#
# F-Droid / CI:
#   NATIVE_OUT    directory produced by tools/build-native.sh; when set, the
#                 Termux proot/talloc/shmem/libpty binaries are not used.
#   SIGN=0        produce an unsigned APK (the F-Droid build server signs it).
#   SKIP_ICON=1   keep the committed launcher icons instead of regenerating.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"
DELIVER_DIR="${DELIVER_DIR-/storage/emulated/0/ATTC OPENCODE}"

ANDROID_JAR="${ANDROID_JAR:-/data/data/com.termux/files/usr/share/java/android.jar}"
SHIZUKU_LIBS="$ROOT/libs/shizuku"
FRAMEWORK="${FRAMEWORK:-/system/framework/framework-res.apk}"
TERMUX_PREFIX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
JNI_INC="${JNI_INC:-$TERMUX_PREFIX/include}"

# Termux ships an android.jar without a resource table, so attribute resolution
# uses the device framework. With an SDK android.jar, use the jar directly.
LINK_I="$ANDROID_JAR"
if [ -f "$FRAMEWORK" ]; then
  LINK_I="$FRAMEWORK"
fi

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
NAME="${NAME:-ATTC-OPENCODE-$VERSION_NAME}"

KS_FILE="${KS_FILE:-$ROOT/attc-opencode.keystore}"
KS_PASS="${KS_PASS:-attcattc}"
KS_ALIAS="${KS_ALIAS:-attc}"

MIN_SDK="${MIN_SDK:-26}"
TARGET_SDK="${TARGET_SDK:-28}"

NATIVE_OUT="${NATIVE_OUT:-}"
SIGN="${SIGN:-1}"
SKIP_ICON="${SKIP_ICON:-0}"

if [ -n "$NATIVE_OUT" ]; then
  PROOT_BIN="$NATIVE_OUT/proot"
  LOADER_BIN="$NATIVE_OUT/loader/loader"
  PTY_SO="$NATIVE_OUT/libpty.so"
  TALLOC_SO="$NATIVE_OUT/libtalloc.so.2"
  SHMEM_SO="$NATIVE_OUT/libandroid-shmem.so"
  for f in "$PROOT_BIN" "$LOADER_BIN" "$PTY_SO" "$TALLOC_SO" "$SHMEM_SO"; do
    if [ ! -s "$f" ]; then
      echo "missing native output: $f (run tools/build-native.sh)" >&2
      exit 1
    fi
  done
else
  PROOT_BIN="$TERMUX_PREFIX/bin/proot"
  LOADER_BIN="$APP/jni/loader"
  PTY_SO="$APP/jni/libpty.so"
  TALLOC_SO="$TERMUX_PREFIX/lib/libtalloc.so.2"
  SHMEM_SO="$TERMUX_PREFIX/lib/libandroid-shmem.so"
fi

for bin in aapt2 javac d8 zip apksigner; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "missing tool: $bin" >&2
    exit 1
  fi
done
if [ ! -f "$ANDROID_JAR" ]; then
  echo "missing android.jar: $ANDROID_JAR" >&2
  exit 1
fi

echo "[0/7] native + icon + local CA"
if [ -z "$NATIVE_OUT" ]; then
  bash "$ROOT/tools/pty.sh"
fi
if [ "$SKIP_ICON" != "1" ]; then
  python3 "$ROOT/tools/make_icon.py"
fi
if [ ! -f "$APP/assets/tls/attc-ipecho.p12" ]; then
  bash "$ROOT/tools/make_certs.sh"
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$BUILD/stage/lib/arm64-v8a" "$OUT_DIR"

echo "[1/7] resources"
aapt2 compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "[2/7] link"
mkdir -p "$APP/assets"
aapt2 link -o "$BUILD/base.apk" \
  -I "$LINK_I" \
  --java "$BUILD/gen" \
  -A "$APP/assets" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --replace-version \
  "$BUILD/res.zip"

echo "[3/7] javac"
find "$APP/src" -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' >> "$BUILD/sources.txt"
SHIZUKU_CP=""
for j in "$SHIZUKU_LIBS"/*.jar; do
  [ -f "$j" ] && SHIZUKU_CP="$SHIZUKU_CP:$j"
done
if ! javac -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR$SHIZUKU_CP" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"; then
  echo "compile failed" >&2
  exit 1
fi

echo "[4/7] d8"
# Vendored Shizuku API/provider classes are merged into the dex.
for j in "$SHIZUKU_LIBS"/*.jar; do
  [ -f "$j" ] && (cd "$BUILD/classes" && unzip -oq "$j")
done
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
d8 --lib "$ANDROID_JAR" --min-api "$MIN_SDK" --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[5/7] package"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
cp "$PTY_SO" "$BUILD/stage/lib/arm64-v8a/libpty.so"
cp "$LOADER_BIN" "$BUILD/stage/lib/arm64-v8a/libproot-loader.so"
cp "$BUILD/dex/classes.dex" "$BUILD/stage/classes.dex"
# proot must live in nativeLibraryDir: app data is not executable (W^X).
python3 "$ROOT/tools/patch_proot.py" \
  "$PROOT_BIN" "$BUILD/stage/lib/arm64-v8a/libproot.so"
cp -L "$TALLOC_SO" "$BUILD/stage/lib/arm64-v8a/libtalloc.so"
cp -L "$SHMEM_SO" "$BUILD/stage/lib/arm64-v8a/libandroid-shmem.so"
(cd "$BUILD/stage" && zip -q "$BUILD/unsigned.apk" classes.dex lib/arm64-v8a/*.so)

echo "[6/7] sign"
if [ "$SIGN" = "1" ]; then
  if [ ! -f "$KS_FILE" ]; then
    keytool -genkeypair -keystore "$KS_FILE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=ATTC, O=ATTC" >/dev/null 2>&1
  fi
  apksigner sign --ks "$KS_FILE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$OUT_DIR/$NAME.apk" "$BUILD/unsigned.apk"
  apksigner verify "$OUT_DIR/$NAME.apk"
else
  cp "$BUILD/unsigned.apk" "$OUT_DIR/$NAME.apk"
  echo "unsigned build (SIGN=0)"
fi

echo "[7/7] deliver"
if [ -n "$DELIVER_DIR" ] && { [ -d "$DELIVER_DIR" ] || mkdir -p "$DELIVER_DIR" 2>/dev/null; }; then
  cp "$OUT_DIR/$NAME.apk" "$DELIVER_DIR/$NAME.apk"
  (cd "$DELIVER_DIR" && sha256sum "$NAME.apk" > SHA256SUMS)
  echo "delivered -> $DELIVER_DIR/$NAME.apk"
fi

echo "sha256 $(sha256sum "$OUT_DIR/$NAME.apk" | cut -d' ' -f1)"
ls -la "$OUT_DIR/$NAME.apk"
echo "OK -> $OUT_DIR/$NAME.apk"
