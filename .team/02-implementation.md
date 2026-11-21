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
