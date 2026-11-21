# 02 - 5.21 uygulama durumu (2026-11-21)

## Cihazda tamamlanan ve dogrulanan

- Gorunen ad: **Attc Code** (aapt2 application-label). Paket kimligi com.attc.opencode korundu.
- Surum: 5.21.0 (versionCode 90). APK: dist/ATTC-CODE-5.21.0.apk, sha256 135c31b52c97aff19b1f2987e7e881e13f5277499677cc9b7d21e929ada14b4d.
- Ses: AudioBridge/AudioTrack tum PTY oturumlarinda ortak. Terminalde paplay testi: audio play rate=16000 ch=1, frames=4800, underruns=0. arecord testi: audio rec rate=16000, 75.820 bayt WAV.
- Sesli komut yonlendirmesi: voiceCli artik aktif oturumu kullanir; sadece Antigravity degil. Terminalde voicecmd testi voice-all.txt = VOICE_ALL_OK.
- Onboarding: ilk acilista 3 sayfa gorunur, ATLA ile kapanir; Ayarlar > Kullanim kilavuzu ile yeniden acilir.
- Claude on kontrolu: opencode binary yokken "opencode gerekli" dialogu cihazda dogrulandi; binary geri yuklendi.

## Bekleyen

- GitHub otomatik guncelleme/kurulum: REQUEST_INSTALL_PACKAGES, PackageInstaller, imza SHA-256 + asset digest + versionCode dogrulama, aktif oturumda erteleme.
- Ucan pencere: SYSTEM_ALERT_WINDOW, canli durum, tiklayinca gizle (oturum surer), pencereden prompt veya bildirim yaniti.
- targetSdk 28 nedeniyle her kurulumda Android sistem onayi zorunlu; guncelleme canli PTY oturumlarini sonlandiriyor.

## Degisen dosyalar

- app/src/com/attc/opencode/TerminalService.java: voiceCli aktif oturum; writeVoicePrompt duz terminalde ham komut.
- app/src/com/attc/opencode/MainActivity.java: onboarding, Claude on kontrolu, tum terminaller icin mikrofon izni, ad.
- app/res/values/strings.xml: app_name = Attc Code.

## MCP final verification (2026-11-21)

- OpenCode 2.3.2 accepts MCP servers at `mcp.servers.<name>` with `disabled: false`; the legacy `mcp.<name>` + `enabled: true` shape was ignored.
- VoiceBridge now merges `mcp.servers.android`, removes the legacy key, copies `android-mcp.py`, and appends the `android_*` hint to AGENTS.md once.
- Device log proof: `mcp connected server=android tools=13`.
- Installed APK: 5.21.0 (90), sha256 `680a2faa24ec430d718cdf2a2db6e330253704b12efc6664ac632258f6a0ad7b`.
- Commit: `99a835d`.

## Mascot states, terminal sound, size control (2026-11-21)

- LogoView has three states driven by PTY activity: sleep (closed eyes/drool), work (open eyes, amber spinner, moving mouth) and done (green check, pulse ring, sparkles).
- Session.readLoop calls OverlayService.noteOutput() per chunk; 6 s of silence marks the task done, 6 s later the mascot sleeps again. CLI exit marks done immediately.
- BEL (0x07) and OSC 9 / OSC 777 notify sequences in PTY output play AudioBridge.chime(): attention, done and error patterns through AudioTrack.
- The paplay/pw-cat shim now treats "-" as stdin instead of a file path.
- overlay_size defaults to 60 (40% smaller): bubble 64dp to 38dp, panel 310dp to 200dp, menu 156dp to 120dp; Settings has a 40-100 size SeekBar.
- Device evidence: overlay created size=60; work mode 1 during tick output; work mode 2 plus chime 1 on completion; BEL test produced audio: chime 0; Settings showed Pencere boyutu: %60; live change logged size 96/49/59/60.
- APK 5.21.0 (90) sha256 ff68213b0180e65a26891dbc5f55cc4814d55ed71b3b97421bca2291e68c5e54.
