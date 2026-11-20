# Changelog

All notable changes to Attc Code are documented here. Versions follow the
`versionName` shipped in the APK; `versionCode` is in parentheses.

## 5.20.0 (89) — 2026-11-20

- **Several CLIs at once.** Every pick now gets its own PTY, terminal buffer
  and exit marker, so opening Antigravity no longer replaces a running
  opencode session. Switching between them keeps each screen, scrollback and
  running command intact.
- **CLI drawer.** A menu button at the top left opens a left-to-right drawer
  that lists every running window (with its own number, CLI name and state)
  plus an "Open another" section for the remaining CLIs. Tapping a row
  switches the visible terminal; tapping a CLI opens a new window.
- **Long replies are no longer cut in half.** `AudioBridge` asked
  `AudioTrack.write()` once per read and dropped the unwritten remainder, then
  stopped the track while audio was still queued. It now loops until every
  byte is written, queues a full second of audio and waits for the playback
  head before `stop()`. The guest `paplay`/`aplay` shim also forwards PCM in
  8 KB blocks instead of 64 KB, so it no longer delivers 1.36 s bursts that
  drained the queue and left an audible hole mid-sentence. An 8-second test
  clip reports `play done frames=192000` instead of stopping after the first
  sentence.
- **Antigravity is the voice default.** With no explicit voice CLI configured,
  the wake word targets Antigravity instead of the last used CLI: it switches
  to a running `agy` session, resumes the one waiting at its prompt, or opens
  a new one — without disturbing the CLI the user is reading.
- **The CLI no longer hears itself.** Antigravity talks through the phone
  speaker, and the phone microphone was feeding that same voice back into
  Ubuntu, so it transcribed its own first sentence as a new user turn and
  repeated it in a loop. While audio is playing — and for 700 ms after — the
  recorder now receives digital silence, and the microphone asks Android for
  echo cancellation via `VOICE_COMMUNICATION` and `AcousticEchoCanceler`.
  A recorded probe stays at exactly zero RMS during playback and keeps ambient
  sound before it, so the gate does not mute the user for good.
- **The microphone is not held hostage by an open player.** The first echo
  guard muted for as long as Antigravity kept its `paplay` socket open; a
  single turn's player can live for minutes, so the CLI went deaf and the
  next answer took over a minute. The gate now follows the queued audio:
  it mutes while frames are waiting in the track, then releases 250 ms after
  the last queued frame. A silent-but-open player no longer blocks speech.
- Per-session `cli-exit-<id>` markers replace the shared marker, and restart,
  boot and fully-close paths release every PTY rather than just the visible
  one.

## 5.19.0 (88) — 2026-11-20

- **The terminal's own audio reaches the phone.** `AudioBridge` runs two
  loopback PCM servers (playback on 4714, capture on 4715) and `VoiceBridge`
  installs `arecord`, `pw-record`, `parec`, `paplay`, `pw-cat` and `aplay`
  shims in Ubuntu. A CLI's original sounds play on the speaker, and its
  microphone gets the phone microphone. The app synthesizes no voice and no
  notification tones of its own.
- **One-shot audio bootstrap.** On the first CLI launch the guest installs
  `python3-minimal`, `alsa-utils` and `pulseaudio-utils` in the background,
  guarded by a marker, so the shims work on a fresh root filesystem.
- **Antigravity auto voice.** A new Advanced option sends `/voice live` about
  12 seconds after Antigravity starts. When the account includes Gemini Live
  this opens the live audio session; otherwise Antigravity prints its own
  notice and the wake-word path keeps typing requests into the CLI.
- **Antigravity setup is automatic.** Terminal sandboxing is disabled,
  `toolPermission` is set to always proceed and `/root` is trusted before
  launch, so `which`, `opencode --version` and other shell tools work without
  manual approval.
- **Wake-word enrollment dialog.** Three guided samples with a live level
  meter, sample counter, vibration, cancel and a clear error message.
- **SHIFT on the key row.** SHIFT works like CTRL and ALT: sticky until the
  next key, with refreshed modifier colours and capitalised letters.
- **Shorter Settings.** The main screen keeps everyday switches and an
  `Advanced…` page holds the wake phrase, voice CLI, Antigravity voice toggle,
  device identity, network tools and diagnostics. `Forget wake word` clears a
  model recorded in a noisy room.
- `VIBRATE` permission, README notes for the audio path and the new Settings
  layout.

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
