# Changelog

All notable changes to Attc Code are documented here. Versions follow the
`versionName` shipped in the APK; `versionCode` is in parentheses.

## 5.18.2 (87) — 2026-11-16

- **Self-healing CLI install.** Launching Claude Code, Kimi Code or
  Antigravity now checks for its launcher first and runs the official
  installer when it is missing, instead of failing with “command not found”.
  Kimi is relinked when its runtime is already unpacked.
- **App updates no longer wipe the Ubuntu rootfs.** When the root marker is
  missing, the base archive is extracted over the existing tree instead of
  deleting it, so installed CLIs, packages and files survive.
- `/root/.kimi-code/bin` joins `/root/.local/bin` on the PATH of every CLI
  session, and the guest `/tmp` keeps its sticky bit.
- Claude Code settings are written without python3: when the interpreter is
  absent, the gateway settings and onboarding flags are written directly.
- **TUIs stay legible after scrolling.** The screen model now writes to the
  visible row instead of the top of the scrollback list, and implements
  DECSTBM scroll regions, insert/delete line inside a region, and reverse
  index. This fixes duplicated lines, overwritten characters and stale help
  text in full-screen menus such as Antigravity's `/model` picker.

## 5.18.1 (86) — 2026-11-16

- **Attach the original file, not a copy.** Picking a photo or file now
  resolves its real Android path for Android 13 photo-picker URIs, DocumentsUI
  document ids, MediaStore rows and FUSE descriptors, so attaching a photo or
  video no longer duplicates it into `uploads/`.
- When a provider genuinely hides the path, the copy fallback is bounded:
  `uploads/` gets a `.nomedia` marker, app-created copies older than 7 days are
  removed, and the folder is kept at or below 100 MB.

## 5.18 (85) — 2026-11-15

- **Explicit download consent.** Before the first Ubuntu root filesystem or CLI
  download, the app now explains that these files come from the network, are
  not part of the APK and are not verified by F-Droid. Nothing is downloaded
  until the user confirms.
- Required by the F-Droid inclusion policy for apps that fetch executable
  components at runtime.

## 5.17 (84) — 2026-11-14

- **F-Droid-ready native build.** proot, talloc and the shared-memory shim are
  now compiled from the vendored sources in `native/` by
  `tools/build-native.sh`, using either the Android NDK or Termux clang. The
  APK layout and runtime behaviour are unchanged.
- The signed Attc Code F-Droid repository is the recommended update channel,
  alongside GitHub Releases.

## 5.16 (83) — 2026-11-14

- **Wake-word fix.** The VAD thresholds were expressed in 16-bit sample units
  while the audio pipeline works on samples normalised to [-1, 1], so no frame
  was ever considered voiced and enrolment always ended with “not enough
  speech”. The thresholds now use the correct scale.
- Enrolment logs live RMS, noise floor and segment counts, and accepts long
  utterances without dropping them.
- DTW now skips length-mismatched pairs early, widens the Sakoe-Chiba band for
  faster/slower speech, and reports a plain miss instead of overflow values.
- `--ez voicediag true` records eight seconds of energy statistics.

## 5.13 (80) — 2026-11-14

- **Selectable wake phrase.** Settings now offers “Hey Attc”, “Hey Attc Code”,
  “Merhaba Attc”, “Selam Attc” and a custom phrase. Changing the phrase clears
  the old voice templates and asks for a fresh three-sample recording.
- **Microphone permission flow.** Enabling the voice assistant requests
  `RECORD_AUDIO`; a permanently denied permission routes to the app settings
  page. A “Grant microphone permission” row appears while access is missing.
- Voice status, notification and the “on” action use the configured phrase.
- `adb` test hook: `--es voicephrase "..."` sets the phrase and clears templates.

## 5.12 (79) — 2026-11-14

- First voice assistant build: offline MFCC/DTW wake word with three-sample
  enrolment, Turkish speech recognition, CLI dispatch, and the Shizuku
  `attc-ctl` bridge for phone tasks.
- Microphone foreground service, partial wake lock, boot restart, notification
  actions, battery-optimisation shortcut and settings section.

## 5.11 (78) — 2026-11-14

- Terminal, CLI chooser and Ubuntu bootstrap updates; delivery naming switched
  to `ATTC-CODE-<version>.apk`.

## 5.1–5.10

- Session chooser, sessions/files panel, attachments, extra keys, text
  selection, pinch zoom, fake-IP privacy mode, quota reset, egress/proxy
  handling and storage/notification self-grants through Shizuku.

Older builds used the `ATTC-OPENCODE-<version>.apk` name.
