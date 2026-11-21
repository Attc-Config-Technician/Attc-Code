# orchestrator log

- 2026-11-20 | research | decision | Started change profile (research, prd, architecture, build, qa) for the 5.21 feature set | .team/01-research.md
- 2026-11-21 | research | fact | Research artifact written after device verification: app 5.20.0/89, no install/overlay permissions, update kills PTYs | .team/01-research.md
- 2026-11-21 | research | decision | Gate 1 recommendation: build with constraints; phased onboarding/update/overlay | .team/01-research.md
- 2026-11-21 | build | fact | 5.21.0/90 built+installed; audio play/rec verified in plain terminal; voice routed to active terminal | dist/ATTC-CODE-5.21.0.apk
- 2026-11-21 | build | fact | Onboarding and Claude prerequisite dialogs verified on emulator-5554 | .team/02-implementation.md
2026-11-21: Fixed OpenCode 2.3.2 MCP registration (mcp.servers + disabled:false), verified mcp connected server=android tools=13, rebuilt/installed APK sha256 680a2faa, committed 99a835d.
2026-11-21: Added work/done mascot animation (PTY activity + 6s idle), BEL/OSC terminal chimes via AudioBridge, paplay "-" stdin fix, overlay_size 60 default with Settings SeekBar; verified on device and installed sha ff68213b.
