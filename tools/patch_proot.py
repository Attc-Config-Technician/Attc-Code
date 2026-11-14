#!/usr/bin/env python3
"""Rewrites proot so it can be shipped as an APK native library.

Android blocks exec() of files in an app's data directory (W^X, targetSdk 29+),
so proot has to live in nativeLibraryDir as libproot.so. That directory can
only hold lib*.so names, so the DT_NEEDED entry for Termux's versioned talloc
is shortened to the plain soname we package next to it.

  usage: patch_proot.py <in> <out>
"""
import sys

OLD = b"libtalloc.so.2\x00"
NEW = b"libtalloc.so\x00\x00\x00"


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, "rb") as f:
        data = bytearray(f.read())
    hits = data.count(OLD)
    if hits != 1:
        print(f"expected exactly one {OLD!r}, found {hits}", file=sys.stderr)
        return 1
    i = data.index(OLD)
    # The replacement is shorter, so pad with NULs: strtab entries are
    # NUL-terminated, and the leftover bytes become an unused string.
    data[i:i + len(OLD)] = NEW + b"\x00" * (len(OLD) - len(NEW))
    with open(dst, "wb") as f:
        f.write(data)
    print(f"patched {dst} (DT_NEEDED libtalloc.so at {i})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
