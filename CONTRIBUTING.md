# Contributing to Attc Code

Attc Code is a normal Android project without Gradle: the APK is assembled with
`aapt2`, `javac`, `d8` and `apksigner`, and the native runtime is compiled from
the sources in `native/`.

Please keep changes focused. Open an issue first for a larger feature so the
approach can be discussed.

## Requirements

- JDK 17 or newer
- Android SDK: platform 28 and build-tools 34.0.0
- Android NDK r28c for the native runtime
- `make`, `zip`, `python3`
- For device builds: Termux with `aapt2`, `d8`, `clang` and `llvm-*`

## Build an unsigned APK (F-Droid path)

This is the same path the F-Droid build server uses:

```bash
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_NDK_HOME="$HOME/android-ndk-r28c"

bash tools/build-fdroid.sh 5.17 84 "$ANDROID_HOME" "$ANDROID_NDK_HOME"
```

The output is `dist/ATTC-CODE-5.17.apk`. It is unsigned; the F-Droid build
server signs it with its own key.

## Build a signed APK locally (Termux)

The native libraries can be built with Termux clang when no NDK is installed:

```bash
bash tools/build-native.sh
NATIVE_OUT="$PWD/build-native" \
  VERSION_CODE=84 VERSION_NAME=5.17 \
  KS_FILE=/path/outside/the/repo/attc.keystore \
  bash tools/build.sh
```

Never commit a keystore, `keystore.properties`, a repository password or an API
token. `.gitignore` already excludes the common signing files.

## Tests

```bash
bash tools/test.sh
```

This runs the pure-Java packet, DNS and plugin tests on a normal JVM. Runtime
changes should also be checked on an arm64 device or emulator: install the APK,
start a Terminal session and confirm the prompt appears.

## Documentation

- Repository text, the website, issue templates and `fastlane/` metadata are in
  English.
- The app UI is Turkish; keep user-facing strings in `app/res/values/`.
- Update `CHANGELOG.md` for user-visible changes and add a matching
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- Screenshots live in `fastlane/metadata/android/en-US/images/` and in
  `docs/assets/`.

## F-Droid

The signed repository is published from `docs/fdroid/repo/`. The official
F-Droid recipe is prepared in a separate `fdroiddata` merge request; do not add
build-server notes to the README or the website.

## License

By contributing you agree that your changes are licensed under
GPL-3.0-or-later, the license of this project.
