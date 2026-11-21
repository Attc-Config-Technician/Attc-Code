# Memory Index

project: Attc Code - Android app that boots Ubuntu 24.04 (proot) and runs AI coding CLIs
stage: research (0/5, Gate 1 pending)
stack: Java/Android (minSdk 26), proot Ubuntu arm64, GitHub Releases, signed self-hosted F-Droid repo
facts:
- Current release 5.20.0 (versionCode 89); repo Attc-Config-Technician/Attc-Code.
- CLIs: opencode, Claude Code, Antigravity (agy), Codex, Kimi; each keeps its own PTY/window.
- Antigravity is the default voice target; audio uses AudioBridge/VoiceBridge.
- Updates ship as signed APKs on GitHub Releases and on the GitHub Pages F-Droid repo; no Play Store.
- App has no Play services dependency; targets SDK 28, tested on Android 13.
- Device 2026-11-21: manifest lacks REQUEST_INSTALL_PACKAGES and SYSTEM_ALERT_WINDOW; POST_NOTIFICATIONS granted.
- opencode 2.3.2 binary at /usr/local/lib/opencode/opencode; /root/.local/bin/opencode missing.
- Device test: adb install -r kills the app and both live PTY/proot processes.
open:
- Feature request 2026-11-20: GitHub auto-update + install; floating live-activity window with prompt input; skippable first-run guide; Claude requires opencode.
- Gate 1 approval pending; then PRD (team/pm).
- Overlay prompt IME/focus and F-Droid updater flavor are the main design risks.
pointers:
- .team/01-research.md - research, device evidence, decision table
- CHANGELOG.md - shipped features through 5.20.0
updated: 2026-11-21
