# 01 — Araştırma: Attc Code 5.21 — otomatik güncelleme, canlı overlay, onboarding

Tarih: 2026-11-21
Profil: change (research → prd → architecture → build → qa)
Durum: tamamlandı; Gate 1 onayı bekliyor
Hazırlayan: team/analyst + orchestrator cihaz doğrulaması

## 1. Yönetici özeti

Attc Code 5.21 için dört istek aynı kullanıcı sorununa dokunuyor: kurulum/güncelleme sürtünmesi, uygulama değiştirirken ajan durumunu görememek, ilk kullanım belirsizliği ve Claude Code opencode binary bağımlılığı.

Karar: **Build with constraints.**
- Faz 1: onboarding + Claude ön kontrolü (düşük risk, yüksek aktivasyon).
- Faz 2: opt-in GitHub güncelleme; sessiz kurulum vaadi yok.
- Faz 3: durum-only overlay MVP; tıklayınca gizlenir, oturum sürer.
- Faz 4: overlay prompt ayrı doğrulama kapısı; IME/odak çözülmezse no-build, bildirim yanıtı fallback.

## 2. Cihaz doğrulaması (2026-11-21)

Cihaz: Xiaomi 2209116AG, Android 13, `emulator-5554`.

| Test | Sonuç |
|---|---|
| Kurulu sürüm | 5.20.0 (versionCode 89), minSdk 26, targetSdk 28 |
| REQUEST_INSTALL_PACKAGES | Manifest yok; appops Default mode: deny |
| SYSTEM_ALERT_WINDOW | Manifest yok; appops default, geçmişte reddedilmiş |
| POST_NOTIFICATIONS | granted=true |
| opencode binary | `/usr/local/lib/opencode/opencode` mevcut, 247.843.832 bayt, opencode v2.3.2 |
| opencode PATH | `/root/.local/bin/opencode` yok; cliInstalled("opencode") yanlış negatif verebilir |
| claude binary | `/root/.local/bin/claude -> /root/.local/share/claude/versions/2.1.325` mevcut |
| PTY güncelleme testi | Önce app pid 22957 + iki libproot (23530, 23556); `adb install -r` sonrası app ve iki PTY yok |

**Kanıt:** Android uygulama güncellemesi süreci sonlandırıyor; canlı PTY oturumları ölüyor. README ifadesi güncelleme için doğru değil; transcript/scrollback ile canlı PTY ayrımı PRD içinde netleşmeli.

## 3. Bulgular

### 3.1 GitHub otomatik güncelleme + kurulum

- Android 12+ ve targetSdk 28 nedeniyle her kurulumda sistem onayı gösterilir. Otomatik güncelleme = tek onaylı kurulum; sessiz kurulum mümkün değil.
- Gerekli: REQUEST_INSTALL_PACKAGES, kullanıcı başına install unknown apps izni, PackageManager.canRequestPackageInstalls(), PackageInstaller session.
- Kurulum öncesi doğrulama: APK imza SHA-256 (API 28+ GET_SIGNING_CERTIFICATES), GitHub asset digest (5.20 APK: sha256:1aa92dba...b50cf), versionCode artışı.
- GitHub API: anonim 60 istek/saat/IP; kimlik doğrulamalı 5.000/saat. releases/latest semver yerine created_at sırasına göre döner; asset indirmeleri REST kotasına sayılmaz (çıkarım).
- Aktif oturum varken güncelleme ertelenmeli/uyarılmalı; aksi halde çalışan iş kaybolur.
- F-Droid kurulumunda updater kapatılmalı veya ayrı flavor; getInstallSourceInfo / getInstallerPackageName ile org.fdroid.fdroid tespiti. F-Droid politikası ek executable indirmeye açık opt-in onayı ister.
- Öneri: varsayılan kapalı, açık rıza metni, yalnızca GitHub Releases, Wi-Fi/şarj opsiyonel, kurulum sonrası yeniden başlatma bildirimi.

### 3.2 Uçan pencere (canlı aktivite overlay)

- SYSTEM_ALERT_WINDOW + Settings.canDrawOverlays(); izin ACTION_MANAGE_OVERLAY_PERMISSION + package URI ile istenmeli.
- API 26+ TYPE_APPLICATION_OVERLAY.
- Android 12+ untrusted touch: pass-through güvenilmez; overlay touchable tasarlanmalı. Opak FLAG_NOT_TOUCHABLE overlay katmanları 0.8 toplam opaklık üstünde engellenir.
- Prompt için focusable EditText/IME gerekir; Tasker dokümanı TextEdit overlay sahnelerinde kullanılamıyor. Android 13/14 odak/IME prototipi şart; başarısızsa bildirim yanıtı veya uygulama içi prompt fallback.
- Android 12+ notification trampoline kısıtı: PendingIntent doğrudan Activity hedeflemeli.
- Minimum MVP: dokunulabilir kompakt pencere; aktif oturum/CLI, süre, son çıktı özeti; tıklayınca gizle; oturum durmaz. Prompt ikinci kapı.
- Rakip/analog: Termux:Float (floating terminal), BubbleSSH (floating SSH), Anthroid (agent runtime + durum banner), Andronix/UserLAnd (Linux-on-Android). Doğrudan birebir rakip yok; Attc farkı yerel + BYO CLI + oturum koruma + agent-aware overlay.

### 3.3 İlk kullanım rehberi

- 3-4 kısa ekran: CLI seçimi, Ubuntu/opencode hazırlığı, sesli komut, gerekli izinler (depolama, overlay, bilinmeyen kaynak).
- Görünür Atla, bir kez gösterim, sonradan yeniden açılabilir; izinler ihtiyaç anında bağlam içinde istenmeli.
- NN/g: onboarding mümkünse atlanabilir, kısa ve opsiyonel olmalı.

### 3.4 Claude Code opencode bağımlılığı (cihazda doğrulandı)

- /usr/local/lib/opencode/opencode mevcut ve v2.3.2; Claude gateway ZenProxy.openGate() bu binary çalıştırıyor.
- /root/.local/bin/opencode yok; naif cliInstalled("opencode") kontrolü yanlış negatif verir. Ön kontrol Setup.ready()/binary yolu üzerinden yapılmalı.
- Claude seçilmeden önce kontrol; eksikse opencode kur CTA; kurulum başarısızsa terminal/diğer CLI fallback; UI ve dokümanda bağımlılık açık.
- claude symlink mevcut; terminal PATH /root/.local/bin içeriyor, ancak non-interactive guest komutlarında PATH farklı olabilir.

### 3.5 Riskler

| Risk | Etki | Azaltım |
|---|---|---|
| Android 12+ sistem onayı | Otomatik kurulum beklentisi karşılanmaz | Tek dokunuş dili; sessiz vaat yok |
| targetSdk 28 | Her kurulumda onay; ileride uyumluluk | targetSdk yükseltme planı |
| Güncelleme PTY öldürür | Aktif iş kaybı | Oturum varken ertele/uyar; README düzelt |
| İmza/digest hatası | Kötü APK kurulumu | Sertifika + asset digest + versionCode |
| F-Droid politikası | Ana repoya kabul sorunu | Opt-in, updatersiz flavor |
| Overlay IME/odak | Prompt çalışmaz | Prototip; bildirim fallback |
| Claude-opencode bağımlılığı | Gateway çalışmaz | Ön kontrol + CTA + fallback |
| Küçük pazar | Düşük yatırım getirisi | Niş odak, abonelik değil |

### 3.6 Metrikler (hipotez)

- İlk açılış → 10 dk içinde ilk CLI: +%20.
- Güncelleme opt-in: ≥%40; tamamlanma: ≥%70.
- Overlay haftalık kullanım (çoklu oturum): ≥%25; prompt başarısı: ≥%80.
- Overlay crash/ANR: <%1.
- Claude ön kontrolünün kendi kendine çözülmesi: ≥%80.

## 4. Karar tablosu

| Başlık | Değerlendirme |
|---|---|
| Çözülen problem | Manuel güncelleme, arka planda ajan durumunu görememe, ilk kullanım belirsizliği, Claude gateway bağımlılığı |
| Kimin için | Android üzerinde root olmadan proot Linux ile Claude Code/Codex/Kimi/opencode çalıştıran geliştiriciler; çoklu oturum kullanan ileri kullanıcılar |
| Fark | Yerel + BYO CLI + oturum koruma + agent-aware overlay; bulut/SSH değil |
| Riskler | Sistem onayı, PTY kaybı, imza, F-Droid, overlay IME, Claude bağımlılığı, küçük pazar |
| Öneri | Build with constraints; Faz 1+2 önce, Faz 3 durum-only, Faz 4 prompt ayrı kapı |
| Güven | Orta; teknik bulgular cihaz/kaynak doğrulamalı, pazar/metrikler çıkarım |

## 5. PRD için açık sorular

1. Güncelleme rızası: metin, varsayılan, sıklık, Wi-Fi/şarj, aktif oturumda erteleme, rollback?
2. F-Droid: ana repo hedefi var mı; ayrı updatersiz flavor mı; F-Droid tespitinde updater tamamen kapatılacak mı?
3. Overlay kapsamı: durum-only MVP yeterli mi; prompt için hangi oturum; çoklu oturumda seçim; bildirim fallback?
4. Onboarding: hangi 3-4 ekran; Skip kalıcı mı; izinler hangi anda; yeniden açma nerede?
5. Claude: ön kontrol ne zaman; CTA hangi ekranda; kurulum başarısızsa fallback; opencode sürümü pinlenecek mi?
6. Sessions preserved: transcript mi canlı PTY mi; güncelleme sırasında ne oluyor; README düzeltilecek mi?
7. Metrikler: privacy-first yerel metrik mi, yalnızca dağıtım verisi mi?
8. targetSdk yükseltme planı ve Android 14/15/16 test matrisi?
9. İmza anahtarı saklama/yedekleme/rotasyon?
10. Overlay prompt güvenliği: prompt injection, oturum çıktısının görünürlüğü?

## 6. Kaynaklar

- Android PackageInstaller: https://developer.android.com/reference/android/content/pm/PackageInstaller
- Android 12 untrusted touch: https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events
- Android 14 minimum target API: https://developer.android.com/about/versions/14/behavior-changes-all#minimum-target-api-level
- Android 12 notification trampolines: https://developer.android.com/about/versions/12/behavior-changes-12#notification-trampolines
- Settings.ACTION_MANAGE_OVERLAY_PERMISSION: https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION
- PackageManager.getInstallSourceInfo: https://developer.android.com/reference/android/content/pm/PackageManager#getInstallSourceInfo(java.lang.String)
- F-Droid Inclusion Policy: https://f-droid.org/en/docs/Inclusion_Policy/
- GitHub REST rate limits: https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- GitHub release assets/digest: https://docs.github.com/en/rest/releases/assets
- Termux:Float: https://f-droid.org/packages/com.termux.window/
- Anthroid: https://github.com/k-l-lambda/anthroid
- UserLAnd: https://f-droid.org/packages/tech.ula/
- NN/g skip onboarding: https://www.nngroup.com/articles/skip-onboarding/
- Claude Code Termux issue #50270: https://github.com/anthropics/claude-code/issues/50270

Not: Analistin tam raporu (rakip tablosu, pazar sinyalleri, tüm açık sorular) bu belgenin kaynağıdır; yazma yetkisi engeli nedeniyle burada özetlenmiştir.
