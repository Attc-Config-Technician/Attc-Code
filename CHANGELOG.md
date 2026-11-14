# Changelog

All notable changes to Attc Code are documented here. Versions follow the
`versionName` shipped in the APK; `versionCode` is in parentheses.

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
