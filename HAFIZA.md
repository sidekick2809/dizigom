# HAFIZA — Alan adları ve site notları

Bu dosya, **alan adı (domain) değiştiğinde ne yapılacağını** hızlıca hatırlamak
için tutulur. Her eklenti kendi `MainAPI` sınıfında `mainUrl` değişkenini
kullanır; site yapısı aynı kaldığı sürece **tek satır** değiştirmek yeterlidir.

Son doğrulama: **14 Kasım 2026**

## Kısa yol: alan adı değiştiğinde

| Eklenti | Dosya | Değiştirilecek satır |
| --- | --- | --- |
| Dizigom | `Dizigom/src/main/kotlin/com/dizigom/DizigomProvider.kt` | `override var mainUrl = "https://www.dizigom.news"` |
| FullHDFilmizlesene | `FullHDFilmizlesene/src/main/kotlin/com/fullhdfilmizlesene/FullHDFilmizleseneProvider.kt` | `override var mainUrl = "https://www.fullhdfilmizlesene.now"` |
| Hdfilmcehennemi | `Hdfilmcehennemi/src/main/kotlin/com/hdfilmcehennemi/HdfilmcehennemiProvider.kt` | `override var mainUrl = "https://www.hdfilmcehennemi.nl"` |
| Filmmakinesi | `Filmmakinesi/src/main/kotlin/com/filmmakinesi/FilmmakinesiProvider.kt` | `override var mainUrl = "https://filmmakinesi.to"` |

Sonra:

1. `build.gradle.kts` içindeki `iconUrl` alanını da yeni alan adına göre düzelt
   (aynı eklentinin `build.gradle.kts` dosyası).
2. Bu dosyadaki alan adı/örnek adresleri güncelle.
3. Derle ve yayınla:

```bash
export JAVA_HOME=/opt/homebrew/opt/jdk17   # veya kendi JDK 17 yolunuz
./gradlew make makePluginsJson
git add -A && git commit -m "Update domain" && git push
```

CI `builds` dalını kendisi günceller; Cloudstream'de yalnızca depoyu yenilemek
yeterlidir (eklentiyi silip yeniden kurmaya gerek yok).

Alternatif alan adları (site yönlendiriyorsa): `.now` ↔ `.com` / `.news`,
`hdfilmcehennemi.nl` ↔ `.mobi` ↔ `.com`, `filmmakinesi.to` ↔ `.com` vb.
Yönlendirme varsa Cloudstream `mainUrl` yine de doğrudan çalışan alan adı
olmalıdır; çünkü `Referer`/`Origin` başlıkları buna göre gönderilir.

## 1) Dizigom (dizigom.news)

- Film: `/film/<slug>`
- Dizi: `/diziler/<slug>/` (sezonlar `?sezon=N`)
- Anime: `/anime/<slug>`
- Arama: `/ara?q=<sorgu>`
- Oynatıcı: sayfadaki `div[data-pv]` + `core.js` → `<oynatıcı>/assets/js/s.php?s=SLUG`
  → `window.__PLAYER__` JSON → `stream` (m3u8) ve altyazılar.
- Cloudflare: oynatıcı isteklerinde `CloudflareKiller()` kullanılır.

## 2) FullHDFilmizlesene (www.fullhdfilmizlesene.now)

- Film: `/film/<slug>/`
- Arama: `/arama/<sorgu>` (boşluk yerine `+`, ör. `/arama/hizli+ve+ofkeli`)
- Listeler:
  - `/` — son eklenenler
  - `/yeni-filmler/<sayfa>`
  - `/en-cok-izlenen-filmler`, `/trend-filmler`, `/populer-filmler`
  - `/filmizle/<tur>-filmleri` ve sayfalama: `/filmizle/<tur>-filmleri/<sayfa>`
  - Doğrulanmış türler: `aksiyon-filmleri`, `komedi-filmleri`, `korku-filmleri`,
    `dram-filmler-izle`, `bilim-kurgu-filmleri`, `romantik-filmler`,
    `gerilim-filmleri`, `yerli-filmler`, `yabanci-filmler`, `4k-filmler`,
    `turkce-dublaj-filmler-1`, `turkce-altyazili-filmler-1`, `dual-filmler-1`
  - Yıl: `/yil/2026-filmleri-izle` (benzeri)
- Liste kartları: `li.film > a.tt`, başlık `.film-title`, yıl `.film-yil`,
  puan `.imdb`, poster `img[src|data-src]`.
  Ana sayfada farklı olarak `a.fut-item` kartları da vardır (kod ikisini de okur).
- Film detayı: JSON-LD (`application/ld+json`, `@type: Movie`) — ad, açıklama,
  görsel, tür, oyuncu, puan, süre (`duration: PT..M`).
- Oynatıcı zinciri:
  1. Sayfada `var scx = {...};` nesnesi bulunur.
  2. Her değer **ROT13 → Base64** ile çözülür (UTF-8). Sonuç ya doğrudan adres
     ya da `<iframe src="...">` kodudur.
  3. `rapidvid.org` embed'i: `jwSetup.sources[0].file = av('...')`.
     `av()` çözümü: `ters çevir → Base64(ISO-8859-1) → her karakterden
     "K9L"[i%3].charCodeAt(0)%5+1 çıkar → Base64(ISO-8859-1)`.
  4. Altyazılar `jwSetup.tracks = [...]` içindeki `file` alanlarıdır
     (`subs.rapidvid.org/...vtt`).
- Not: `subs.rapidvid.org` Cloudflare ile korunur; kod `Cookie` başlığını
  `CookieManager` üzerinden eklemeyi dener. Altyazı görünmezse sorun buradadır,
  akış (m3u8) etkilenmez.

## 3) Hdfilmcehennemi (www.hdfilmcehennemi.nl)

- Film: `/<slug>-<id>/` (ör. `/the-odyssey-3/`)
- Arama: `/search?q=<sorgu>` → **JSON** `{"query": "...", "results": ["<a class=\"search-result\" ...>", ...]}`
  (istek `X-Requested-With: fetch` başlığı ile yapılır; kart `a.poster[href]`,
  başlık `img[alt]` ("Ad - Orjinal Ad"), yıl `.year`, puan `.imdb`).
- Listeler:
  - `/` — ana sayfa
  - `/category/film-izle-2/` — filmler
  - `/dil/turkce-dublajli-film-izleyin-7/`, `/dil/turkce-altyazili-filmleri-izleme-sitesi-4/`
  - `/tur/<tur>/` (ör. `aksiyon-filmleri-izleyin-9`, `komedi-filmlerini-izleyin-3`,
    `korku-filmlerini-izle-12`, `dram-filmlerini-izle-4`,
    `bilim-kurgu-filmlerini-izleyin-9`, `gerilim-filmlerini-izle-5`,
    `romantik-filmleri-izle-4`, `savas-filmleri-izle-6`)
  - `/yil/<yıl>-.../`
  - **Sayfalama yok**: liste sayfaları "load more" ile çalışır, HTML'de sayfa
    bağlantısı bulunmaz; bu yüzden yalnızca 1. sayfa okunur.
- Liste kartları: `a.poster[href]`, başlık `title` özniteliği, yıl/puan
  `.poster-meta span` ve `.imdb`.
- Film detayı: JSON-LD `Movie` + `h1` + `og:image`.
- Oynatıcı zinciri:
  1. Sayfada `<iframe data-src="https://hdfilmcehennemi.mobi/video/embed/<id>/?rapidrame_id=...">`.
     (iframe alan adı ana alan adından farklı olabilir; `Referer` film sayfasıdır.)
  2. Embed HTML'inde `sources: [{file: <DEĞİŞKEN>, type: "hls"}]` ve
     `var <DEĞİŞKEN> = <decoder>("...".split("X"));` bulunur.
     **Ayırıcı sabit değildir:** `!`, `~`, `@`, `^` gibi karakterler sayfaya
     göre değişir; kod ayırıcıyı `.split("X")` ifadesinden okur ve çözücü
     fonksiyonun gövdesindeki harf tabanını (`- 96` / `- 64`) çıkarır.
  3. "autocdn" çözücüsü uygulanır (aşağıya bakın).
  4. Sonuç: `https://srv9.cdnimagesXXXX.shop/hls/<ad>/txt/master.txt?s=...`
     (Cloudflare yok, doğrudan oynar).
  5. Altyazılar: `tracks: [{file: "https://hdfilmcehennemi.mobi/vtt/...vtt", label: ...}]`
     — bu adresler doğrudan erişilebilir (test: HTTP 200).

## 4) Filmmakinesi (filmmakinesi.to)

- Film: `/film/<slug>/`
- Arama: `/arama/?s=<sorgu>`
- Listeler:
  - `/` — ana sayfa (slider `a.slide` + liste `a.item`)
  - `/filmler-fmuyqb/`, sayfalama: `/filmler-fmuyqb/sayfa/<n>/`
  - `/tur/<tur>/film/`, sayfalama: `/tur/<tur>/film/sayfa/<n>/`
    (`sayfa/1/` **404** verir; 1. sayfa temel adrestir)
  - `/yil/<yıl>/film/` (ör. `/yil/2026-fm3daq/film/`)
  - Doğrulanmış türler: `aksiyon-fmkwm8`, `komedi-fma6cz`, `korku-fmr7du`,
    `dram-fm1`, `bilim-kurgu-fmsq8z`, `gerilim-fmqd8y`, `romantik-fm1`,
    `savas-fm1`, `macera-fm1`
- Liste kartları: `a.item[href]` — `data-title`, `data-score`, `data-duration`
  (dakika), `data-type` (türler), `data-actor`, `data-country`; poster `img`.
  Ana sayfa slider'ı `a.slide` + `.title`.
- Film detayı: JSON-LD `Movie` (`dateCreated`, `duration: PT..M`, `aggregateRating`,
  `actor`, `image`).
- Oynatıcı zinciri:
  1. Sayfada `.video-parts a[data-video_url]` seçenekleri:
     - **DUAL Close** → `https://closeload.filmmakinesi.to/video/embed/<id>/?imdb_id=...`
     - **DUAL Rapid** → `https://rapid.filmmakinesi.to/embed-.../`
  2. Close embed'i "autocdn" şablonu kullanır (hdfc ile **aynı** algoritma ama
     host `closeload.filmmakinesi.to`). Sonuç: `https://srv9.cdnimagesXXX.cyou/...master.txt?s=...`
  3. Altyazılar `tracks` içinde `closeload.filmmakinesi.to/vtt/...vtt` olarak gelir;
     bu adresler Cloudflare ile korunur (curl 403). Kod `Cookie` başlığı ekler.
  4. **Rapid seçeneği tarayıcı dışından 403** dönebilir; Close seçeneği asıl kaynaktır.
     Kod iki seçeneği de dener, çalışanı ekler.

## "autocdn" çözücüsü (hdfc + filmmakinesi)

Girdi: `encoded` (embed HTML'inden), `delimiter` (sayfadaki `.split("X")`
değeri — `!` sabit değildir, `~` / `@` / `^` olabilir), `rotBase` (çözücü
gövdesindeki harf tabanı, genelde `96`) ve `host` (embed alan adı — **zorunlu**).

```
parts = encoded.split(delimiter)
size  = parts.size - 2
pvqo  = size % 7
h4k   = 8 + (size % 5)
sb5   = parts.removeAt(h4k)
ff8   = parts.removeAt(pvqo) + host
za4   = parts.join("")
if (ff8.length > 4096) za4 = base64decode(za4, ISO-8859-1)

// ff8 üzerinden iki "tohum"
hz = 0; m = 0
for i, p in ff8: hz = (hz*37 + p) % 241
                m  = (m + ((p<<1) xor i)) & 255

y = (hz*3 + m) % 256
q = (m % 11) + 5
o = ((m*251 + hz) % 65519) + 1

// sb5 karakterlerine göre dönüşüm (sondan başa)
for c in reverse(sb5):
    '7' → za4 = base64decode(za4, ISO-8859-1)
    '3' → za4 = reverse(za4)
    diğer → za4 = harf kaydır: shift = (26 - ((ord(c)-rotBase) % 26)) % 26
if (sb5.length > 2048) za4 = reverse(za4)

// karıştırma (shuffle)
for i in n-1 downTo 1: o = (o*97 + 41) % 65519; swaps[i] = o % (i+1)
uygula: for i in 1..n-1: swap(chars[i], chars[swaps[i]])

// XOR
u = y
for p in chars: u = (u*5 + q) % 256; out += chr(p xor u); u = (u + p) % 256
```

Kod tarafı: `decodeAutoCdn()` (her iki provider'da birebir aynı).

## Hızlı doğrulama (tarayıcı ile)

1. Film sayfasını aç, kaynak iframe/`scx`/`data-video_url` değerini bul.
2. Embed sayfasını **iframe içinde** aç (doğrudan açmak Cloudflare 403 verebilir).
3. Embed HTML'inde `sources` ve `tracks` bloklarını gör.
4. Çözülen `master.txt` adresini `Referer: <embed>` başlığıyla `curl` ile dene;
   `#EXTM3U` görüyorsan akış sağlam.
5. Altyazı `.vtt` adresini dene; 403 ise yalnızca altyazı etkilenir.

## Bilinen sınırlar

- Üç sitede de **yalnızca film** (`tvTypes = ["Movie"]`) desteklenir.
- Cloudflare koruması nedeniyle tüm istekler `CloudflareKiller()` interceptor'ı
  ile yapılır; WebView ilk seferde açılır, bu yüzden ilk yükleme yavaş olabilir.
- Altyazı sunucuları (`subs.rapidvid.org`, `closeload.filmmakinesi.to/vtt/...`)
  Cloudflare arkasında; çerezle bile 403 dönebilir.
- Hdfilmcehennemi listelerinde sayfalama yoktur (yalnızca 1. sayfa).
