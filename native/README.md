# Native runtime sources

These trees are vendored so the APK can be built without Termux packages:

| Directory | Component | Upstream | License |
|---|---|---|---|
| `proot/` | Termux fork of PRoot | https://github.com/termux/proot | GPL-2.0-or-later |
| `talloc/` | Samba talloc (reduced source tree) | https://www.samba.org/ftp/talloc/ | LGPL-3.0-or-later |
| `libandroid-shmem/` | SysV shared-memory shim for Android | https://github.com/termux/libandroid-shmem | BSD-3-Clause |

`tools/build-native.sh` compiles them for `arm64-v8a` with either the Android
NDK or Termux clang and writes:

```text
build-native/
  proot
  loader/loader
  libtalloc.so.2
  libandroid-shmem.so
  libpty.so
```

`tools/build.sh` consumes that directory through `NATIVE_OUT` and packages the
libraries into `lib/arm64-v8a/`. The proot ELF is renamed to `libproot.so` and
`tools/patch_proot.py` shortens its `DT_NEEDED` entry for talloc, because
Android only extracts `lib*.so` files from an APK to `nativeLibraryDir`.

Do not add prebuilt binaries here. The build server scans the source tree, and
the whole point of this directory is that the native runtime is built from
source.
