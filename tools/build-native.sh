#!/usr/bin/env bash
# Builds the arm64-v8a native pieces that tools/build.sh packages into the APK:
#
#   proot               the Termux fork of PRoot
#   loader/loader       PRoot's freestanding loader
#   libtalloc.so.2      talloc shared library proot links against
#   libandroid-shmem.so SysV shared-memory shim used by proot's sysvipc extension
#   libpty.so           the JNI pseudo-terminal used by the terminal view
#
# Two toolchains are supported and produce the same layout:
#
#   Android NDK  official F-Droid / CI builds. Set NDK, ANDROID_NDK_HOME or
#                ANDROID_NDK to the NDK root, or pass NDK=/path/to/ndk.
#   Termux clang local device builds. Used when no NDK is configured.
#
# Environment:
#   NATIVE_OUT    output directory (default: <repo>/build-native)
#   NATIVE_API    Android API level (default: 26)
#   NATIVE_JOBS   make parallelism (default: nproc)
#   NATIVE_KEEP   set to 1 to reuse the work directory
#
# Output is consumed by tools/build.sh through NATIVE_OUT.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${NATIVE_OUT:-$ROOT/build-native}"
BUILD="${NATIVE_BUILD:-$ROOT/.work/native}"
API="${NATIVE_API:-26}"
JOBS="${NATIVE_JOBS:-$(nproc 2>/dev/null || echo 4)}"

PROOT_SRC="${PROOT_SRC:-$ROOT/native/proot}"
TALLOC_SRC="${TALLOC_SRC:-$ROOT/native/talloc}"
SHMEM_SRC="${SHMEM_SRC:-$ROOT/native/libandroid-shmem}"
JNI_DIR="$ROOT/app/jni"

NDK_ROOT="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
if [ -z "$NDK_ROOT" ]; then
  for base in "${ANDROID_HOME:-}/ndk" "${ANDROID_SDK_ROOT:-}/ndk" "$HOME/Android/Sdk/ndk"; do
    [ -d "$base" ] || continue
    NDK_ROOT="$(ls -d "$base"/*/ 2>/dev/null | sort -V | tail -1)"
    [ -n "$NDK_ROOT" ] && break
  done
fi

if [ -n "$NDK_ROOT" ] && [ -d "$NDK_ROOT" ]; then
  TOOLCHAIN=""
  for host_tag in linux-x86_64 linux-aarch64 darwin-x86_64 darwin-arm64; do
    if [ -d "$NDK_ROOT/toolchains/llvm/prebuilt/$host_tag" ]; then
      TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$host_tag"
      break
    fi
  done
  if [ -z "$TOOLCHAIN" ]; then
    echo "no LLVM toolchain under $NDK_ROOT/toolchains/llvm/prebuilt" >&2
    exit 1
  fi
  CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
  AR="$TOOLCHAIN/bin/llvm-ar"
  RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  STRIP="$TOOLCHAIN/bin/llvm-strip"
  OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
  OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
  READELF="$TOOLCHAIN/bin/llvm-readelf"
  JNI_INC="$TOOLCHAIN/sysroot/usr/include"
  TOOLCHAIN_NAME="Android NDK ($NDK_ROOT)"
else
  PREFIX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
  if [ ! -x "$PREFIX/bin/clang" ]; then
    echo "no NDK configured and no Termux clang at $PREFIX/bin/clang" >&2
    echo "set NDK=/path/to/android-ndk to build on a normal Linux host" >&2
    exit 1
  fi
  # Termux clang resolves ld.lld and helper tools from its own prefix, and
  # loader-info.awk needs the Termux gawk (mawk is not compatible).
  export PATH="$PREFIX/bin:$PATH"
  CC="$PREFIX/bin/clang"
  AR="$PREFIX/bin/llvm-ar"
  RANLIB="$PREFIX/bin/llvm-ranlib"
  STRIP="$PREFIX/bin/llvm-strip"
  OBJCOPY="$PREFIX/bin/llvm-objcopy"
  OBJDUMP="$PREFIX/bin/llvm-objdump"
  READELF="$PREFIX/bin/llvm-readelf"
  JNI_INC="$PREFIX/include"
  TOOLCHAIN_NAME="Termux clang ($PREFIX)"
fi

for t in "$CC" "$AR" "$STRIP" "$OBJCOPY" "$OBJDUMP"; do
  if [ ! -x "$t" ]; then
    echo "missing tool: $t" >&2
    exit 1
  fi
done
if [ ! -f "$JNI_INC/jni.h" ]; then
  echo "missing jni.h under $JNI_INC" >&2
  exit 1
fi
for s in "$PROOT_SRC/src/GNUmakefile" "$TALLOC_SRC/talloc.c" "$SHMEM_SRC/shmem.c"; do
  if [ ! -f "$s" ]; then
    echo "missing source: $s" >&2
    exit 1
  fi
done

echo "toolchain: $TOOLCHAIN_NAME"
echo "output:    $OUT"

mkdir -p "$OUT"
if [ "${NATIVE_KEEP:-0}" != "1" ]; then
  rm -rf "$BUILD"
fi
mkdir -p "$BUILD"

# --- talloc -----------------------------------------------------------------
# talloc is built as a shared library with the Termux-compatible soname
# libtalloc.so.2. tools/patch_proot.py later shortens proot's DT_NEEDED entry
# to libtalloc.so because nativeLibraryDir only accepts lib*.so names.
echo "[1/5] talloc"
(
  cd "$TALLOC_SRC"
  "$CC" -c -I. -Ilib/replace -include config.h -DHAVE_CONFIG_H -D_GNU_SOURCE -fPIC \
    -Wall -O2 talloc.c -o "$BUILD/talloc.o"
  "$CC" -shared -Wl,-soname,libtalloc.so.2 \
    -o "$OUT/libtalloc.so.2" "$BUILD/talloc.o"
)
ln -sf libtalloc.so.2 "$OUT/libtalloc.so"

# --- libandroid-shmem -------------------------------------------------------
# The header must be visible as <sys/shm.h> while proot is compiled; Android
# has no SysV shared memory header of its own.
echo "[2/5] libandroid-shmem"
mkdir -p "$BUILD/shmem-include/sys"
cp "$SHMEM_SRC/shm.h" "$BUILD/shmem-include/sys/shm.h"
"$CC" -shared -fPIC -std=c11 -O2 -Wall -Wextra \
  -Wl,-soname,libandroid-shmem.so \
  -Wl,--version-script="$SHMEM_SRC/exports.txt" \
  -I"$SHMEM_SRC" \
  -o "$OUT/libandroid-shmem.so" "$SHMEM_SRC/shmem.c" -llog -landroid

# --- proot + loader ---------------------------------------------------------
# Build out of tree so the vendored source stays clean. The NDK's headers are
# stricter than glibc's; ashmem_memfd.c relies on string.h being pulled in by
# another header on glibc.
echo "[3/5] proot"
rm -rf "$BUILD/proot"
cp -a "$PROOT_SRC" "$BUILD/proot"
sed -i '1i #include <string.h>' \
  "$BUILD/proot/src/extension/ashmem_memfd/ashmem_memfd.c" 2>/dev/null || true

# Android 15 requires 64-byte TLS alignment on arm64. An aligned TLS variable
# forces the linker to emit a TLS segment with the right alignment.
echo "__thread int __tls_align_fix __attribute__((aligned(64))) = 0;" | \
  "$CC" -fno-emulated-tls -c -x c - -o "$BUILD/tls_align.o"

(
  cd "$BUILD/proot/src"
  make -j"$JOBS" \
    CC="$CC" \
    LD="$CC" \
    STRIP="$STRIP" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I\$(VPATH) -I$BUILD/shmem-include -I$TALLOC_SRC" \
    CFLAGS="-g -Wall -O2 -DWITH_LIBANDROID_SHMEM -I$TALLOC_SRC" \
    LDFLAGS="-L$OUT -ltalloc -landroid-shmem $BUILD/tls_align.o" \
    PROOT_WITH_LIBANDROID_SHMEM=1 \
    CARE_LDFLAGS= \
    HAS_SWIG= \
    HAS_PYTHON_CONFIG= \
    proot
)
cp "$BUILD/proot/src/proot" "$OUT/proot"
if [ ! -f "$BUILD/proot/src/loader/loader" ]; then
  echo "proot built but loader/loader is missing" >&2
  exit 1
fi
mkdir -p "$OUT/loader"
cp "$BUILD/proot/src/loader/loader" "$OUT/loader/loader"

# Keep the debug symbols out of the APK; the DT_NEEDED table is untouched.
"$STRIP" "$OUT/proot" "$OUT/loader/loader"

# --- libpty -----------------------------------------------------------------
echo "[4/5] libpty"
"$CC" -shared -fPIC -O2 -fvisibility=hidden \
  -Wl,-soname,libpty.so \
  -I"$JNI_INC" \
  -o "$OUT/libpty.so" "$JNI_DIR/pty.c" -lutil

# --- verify -----------------------------------------------------------------
echo "[5/5] verify"
for f in proot loader/loader libtalloc.so.2 libandroid-shmem.so libpty.so; do
  if [ ! -s "$OUT/$f" ]; then
    echo "missing output: $OUT/$f" >&2
    exit 1
  fi
done

"$READELF" -d "$OUT/proot" | grep NEEDED || true
printf 'sha256 proot:      %s\n' "$(sha256sum "$OUT/proot" | cut -d' ' -f1)"
printf 'sha256 loader:     %s\n' "$(sha256sum "$OUT/loader/loader" | cut -d' ' -f1)"
printf 'sha256 libtalloc:  %s\n' "$(sha256sum "$OUT/libtalloc.so.2" | cut -d' ' -f1)"
printf 'sha256 shmem:      %s\n' "$(sha256sum "$OUT/libandroid-shmem.so" | cut -d' ' -f1)"
printf 'sha256 libpty:     %s\n' "$(sha256sum "$OUT/libpty.so" | cut -d' ' -f1)"
echo "OK -> $OUT"
