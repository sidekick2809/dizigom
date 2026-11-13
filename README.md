# Dizigom — Cloudstream eklentisi

[Cloudstream](https://github.com/recloudstream/cloudstream) için `https://www.dizigom.news`
kaynağını kullanan bir sağlayıcı (provider) eklentisi.

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

# Eklenti paketi: Dizigom/build/Dizigom.cs3
./gradlew Dizigom:make

# Depo için plugins.json: build/plugins.json
./gradlew makePluginsJson
```

`Dizigom.cs3` dosyasını doğrudan cihaza kopyalayıp Cloudstream'in eklenti
yöneticisinden kurabilirsiniz (Cloudstream'in dosya erişim izni açık olmalı).

## GitHub üzerinden yayınlama

1. Bu projeyi bir GitHub deposuna push edin (`main` veya `master`).
2. **Settings → Actions → General** altında Actions'ı ve
   **Read and write permissions** ayarını açın.
3. `.github/workflows/build.yml` her push'ta eklentiyi derleyip `builds`
   dalına `Dizigom.cs3` ve `plugins.json` olarak yazar.
4. Cloudstream'de **Depo ekle** kısmına `repo.json` içindeki adresi girin
   (aşağıdaki `KULLANICI/REPO` kısmını kendi deponuzla değiştirin).

`repo.json`:

```json
{
  "name": "Dizigom",
  "description": "Dizigom eklentisi",
  "manifestVersion": 1,
  "pluginLists": [
    "https://raw.githubusercontent.com/KULLANICI/REPO/builds/plugins.json"
  ]
}
```

Yerelde `plugins.json` üretirken depo adresini belirtmek için:

```bash
REPO=KULLANICI/REPO ./gradlew makePluginsJson
```

## Notlar

- Site HTML yapısını değiştirirse `DizigomProvider.kt` içindeki seçiciler
  (`a.group.block`, `#bolumler`, `div[data-pv]`, `core.js`) güncellenmelidir.
- Oynatıcı alan adı değişirse kod bunu `core.js` betiğinin adresinden otomatik
  bulur; yalnızca hiç betik bulunamazsa yedek adres kullanılır.
- Bu proje yalnızca kişisel kullanım için bir istemci eklentisidir; içerik
  barındırmaz.
