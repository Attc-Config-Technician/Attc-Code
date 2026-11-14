# F-Droid yayını

Bu klasör, Attc Code'un F-Droid ekosisteminde yayınlanması için gereken
dosyaları ve kalan adımları içerir. Depoda hazır olanlar:

- `fastlane/metadata/android/{en-US,tr-TR}/` — başlık, kısa/uzun açıklama,
  sürüm notu, video ve ekran görüntüleri.
- `LICENSE` — GPL-3.0-or-later.
- `fdroid/com.attc.opencode.yml` — fdroiddata tarifinin taslağı.
- `docs/` — GitHub Pages açılış sayfası ve tanıtım videosu.

## Neden henüz resmî listede değil?

F-Droid, uygulamayı **kendi derleme sunucusunda kaynaktan** üretir ve kendi
anahtarıyla imzalar. Attc Code'un derlemesi Gradle değil; `tools/build.sh`
Termux ortamındaki `aapt2`, `d8`, `apksigner`, `javac` araçlarını ve Termux'un
**proot / libtalloc / libandroid-shmem** ikililerini kullanır. F-Droid'in
Debian tabanlı derleme imajında bu ikililer ve Termux başlıkları yoktur.

İki gerçekçi yol var:

### 1. F-Droid resmî listesi (uzun yol)

1. Depoya `v5.13` etiketi koyun ( `git tag v5.13 && git push origin v5.13` ).
2. Derlemeyi F-Droid ortamına uyarlayın: ya proot'u kaynaktan derleyen bir
   `srclib`/`prebuild` adımı ekleyin, ya da proot ve JNI bağımlılıklarını
   NDK ile derleyen bir Gradle/CMake hattı ekleyin. Tarif taslağındaki
   `disable:` satırını ancak bu adım yeşile döndükten sonra kaldırın.
3. [fdroiddata](https://gitlab.com/fdroid/fdroiddata) deposunu çatallayın,
   `metadata/com.attc.opencode.yml` dosyasını ekleyin, `fdroid lint` ve
   `fdroid build com.attc.opencode:80` çalıştırın.
4. Merge request açın. İnceleme, uygulamanın özgür yazılım olmasını,
   yinelenebilir derlemeyi ve ağ bağımlılıklarının belgelenmesini ister.
   Yapay zekâ ağ geçitleri için `NonFreeNet` gibi bir anti-feature
   işaretlenmesi beklenebilir.

### 2. IzzyOnDroid (hızlı yol, geliştirici imzalı APK)

IzzyOnDroid, geliştiricinin kendi imzaladığı APK'ları kabul eden bir F-Droid
uyumlu depodur. Attc Code'un mevcut imzalı APK'sı ve `fastlane` metinleri
buna uygundur:

1. APK'yı bir GitHub Release'e yükleyin (`.github/workflows/release.yml`
   `v*` etiketinde bunu otomatik yapar).
2. [IzzyOnDroid başvuru issue'su](https://codeberg.org/IzzyOnDroid/repo/issues)
   açın; depo URL'sini, APK release bağlantısını ve lisansı belirtin.
3. Uygulama, F-Droid istemcisine `https://apt.izzysoft.de/fdroid/repo`
   deposu eklenerek kurulabilir.

### 3. Kendi F-Droid deponuz (alternatif)

`fdroidserver` ile kendi deponuzu imzalayıp GitHub Pages üzerinden
yayınlayabilirsiniz:

```bash
fdroid init
fdroid update --create-metadata
fdroid server update
```

Bu yol, mağaza listelenmesi için F-Droid incelemesi gerektirmez; kullanıcılar
depo adresini F-Droid istemcisine ekler. İmzalama anahtarını asla depoya
koymayın.

## Yayın öncesi kontrol listesi

- [ ] `LICENSE` dosyası ve kaynak kod deposu herkese açık.
- [ ] `v5.13` etiketi ve GitHub Release'te imzalı APK var.
- [ ] `fastlane` metinleri iki dilde dolu; ekran görüntüleri 1080×2400.
- [ ] `docs/` GitHub Pages'te yayında; video ve poster erişilebilir.
- [ ] Gizli anahtar, token, keystore ve kişisel veri depoda yok.
- [ ] F-Droid derleme hattı (proot/NDK) yeşil; `fdroid lint` temiz.
