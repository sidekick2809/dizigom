# Dizigom — Cloudstream eklentileri

[Cloudstream](https://github.com/recloudstream/cloudstream) için Türkçe kaynak
eklentileri. Dört ayrı eklenti (her biri bağımsız `.cs3` paketi) tek depodan
sunulur:

| Eklenti | Site | İçerik |
| --- | --- | --- |
| Dizigom | `dizigom.news` | Film, dizi, anime |
| FullHDFilmizlesene | `fullhdfilmizlesene.now` | Film |
| Hdfilmcehennemi | `hdfilmcehennemi.nl` | Film |
| Filmmakinesi | `filmmakinesi.to` | Film |

> Alan adları sık değişiyor. Güncelleme adımları ve tüm site notları için
> **[HAFIZA.md](HAFIZA.md)** dosyasına bakın.

## Dizigom

- Filmler: `/film/<slug>`
- Diziler: `/diziler/<slug>/` (sezonlar `?sezon=N`)
- Animeler: `/anime/<slug>` (sezonlar `?sezon=N`)
- Bölümler: `/<dizi>-<s>-sezon-<b>-bolum/` ve `/anime/<slug>/sezon-<s>/bolum-<b>`
- Arama: `/ara?q=<sorgu>`

## Nasıl çalışıyor?

Site, oynatıcıyı HTML kaynağına doğrudan koymuyor. Sayfada yalnızca opak bir
`<div data-pv="SLUG">` ve `core.js` betiği bulunuyor. Eklenti şu zinciri izliyor:

1. Bölüm/film sayfası indirilir.
2. `data-pv` değeri ve `core.js` adresinden oynatıcı sunucusu bulunur.
3. `<oynatıcı>/assets/js/s.php?s=SLUG` adresinden oynatıcı sayfası alınır
   (site origin'i `Referer` olarak gönderilir).
4. Sayfadaki `window.__PLAYER__` JSON'undan m3u8 (`stream`) ve altyazı (VTT)
   adresleri çıkarılır.
5. Cloudstream'e HLS bağlantısı ve altyazılar verilir.

## Derleme

Gereksinimler: JDK 17 ve Android SDK (compileSdk 35).

```bash
export JAVA_HOME=/path/to/jdk17
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Tüm eklenti paketleri: <Modül>/build/<Modül>.cs3
./gradlew make

# Depo için plugins.json: build/plugins.json
./gradlew makePluginsJson
```

`<Modül>.cs3` dosyasını doğrudan cihaza kopyalayıp Cloudstream'in eklenti
yöneticisinden kurabilirsiniz (Cloudstream'in dosya erişim izni açık olmalı).
Normalde buna gerek yoktur: depo adresini ekleyip listeden kurmak yeterlidir.

## GitHub üzerinden yayınlama

1. Bu projeyi bir GitHub deposuna push edin (`main` veya `master`).
2. **Settings → Actions → General** altında Actions'ı ve
   **Read and write permissions** ayarını açın.
3. `.github/workflows/build.yml` her push'ta tüm eklentileri derleyip `builds`
   dalına `<Eklenti>.cs3` dosyalarını ve `plugins.json` listesini yazar.
   **Önemli:** Cloudstream bir eklentiyi yalnızca `version` değeri arttığında
   günceller. Her yayından önce ilgili modülün `build.gradle.kts` dosyasındaki
   `version = N` satırını bir artırın.

Install address (Cloudstream → Depo ekle):

```
https://raw.githubusercontent.com/sidekick2809/dizigom/main/repo.json
```

`raw.githubusercontent.com` bazı ağlarda (ör. Türkiye'deki bazı ISS'ler)
engellenebilir. Bu durumda Cloudstream "Depo bulunamadı" hatası verir; jsDelivr
aynasını kullanın:

```
https://cdn.jsdelivr.net/gh/sidekick2809/dizigom@main/repo-jsdelivr.json
```

Tek eklenti elle kurmak için yedek paket: `dist/Dizigom.cs3`.

`repo.json` (bu deponun gerçek dosyası):

```json
{
  "name": "Dizigom",
  "description": "Dizigom, FullHDFilmizlesene, Hdfilmcehennemi ve Filmmakinesi eklentileri",
  "manifestVersion": 1,
  "pluginLists": [
    "https://raw.githubusercontent.com/sidekick2809/dizigom/builds/plugins.json"
  ]
}
```

jsDelivr aynası (`repo-jsdelivr.json`) CI tarafından üretilen
`builds/plugins-jsdelivr.json` dosyasını kullanır; eklenti ve liste adresleri
otomatik olarak `cdn.jsdelivr.net` üzerine yazılır.

Yerelde `plugins.json` üretirken depo adresini belirtmek için:

```bash
REPO=sidekick2809/dizigom ./gradlew makePluginsJson
```

## Notlar

- Site HTML yapısı değişirse ilgili `*Provider.kt` içindeki seçiciler
  (`HAFIZA.md` dosyasında listelidir) güncellenmelidir.
- Oynatıcı alan adı değişirse Dizigom bunu `core.js` betiğinin adresinden otomatik
  bulur; yalnızca hiç betik bulunamazsa yedek adres kullanılır.
- FullHDFilmizlesene / Hdfilmcehennemi / Filmmakinesi oynatıcıları, akış adresini
  sayfaya gömülü kodlanmış bir değerden çözer. Algoritma ayrıntıları `HAFIZA.md`
  içindedir.
- Üç film sitesi Cloudflare korumalıdır. Eklentiler, sayfaları gerektiğinde
  görünmez bir WebView ile açar (`CloudflareBypass`); bu yüzden ilk yükleme
  birkaç saniye sürebilir. Cloudstream'in yerleşik `CloudflareKiller` sınıfı
  bilerek kullanılmaz — nedenleri `HAFIZA.md` içindedir.
- Bu proje yalnızca kişisel kullanım için bir istemci eklentisidir; içerik
  barındırmaz.
