package com.fullhdfilmizlesene

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import android.util.Base64

/**
 * FullHDFilmizlesene (https://www.fullhdfilmizlesene.now) sağlayıcısı.
 *
 * Akış:
 *  - Film sayfası: /film/<slug>/
 *  - Arama:        /arama/<sorgu>  (boşluklar "+" ile)
 *  - Liste:        /yeni-filmler/<sayfa> , /filmizle/<tur>-filmleri , /en-cok-izlenen-filmler
 *
 * Oynatıcı kaynakları sayfaya gömülü `var scx = {...}` nesnesindedir. Her kaynak
 * değeri ROT13 + Base64 ile kodlanmıştır. Çözülen değer ya doğrudan bir adres ya da
 * `<iframe src="...">` kodu olur. rapidvid.org adresleri ayrıca `av()` fonksiyonu ile
 * çözülen bir HLS adresi barındırır.
 *
 * Alan adı değişirse yalnızca [mainUrl] güncellenmelidir (bkz. HAFIZA.md).
 */
class FullHDFilmizleseneProvider : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val PAGE_PLACEHOLDER = "{page}"

        private val mapper by lazy { jacksonObjectMapper() }

        private val requestHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )
    }

    // CloudflareKiller tek örnek olarak tutulur; her istekte yeniden oluşturulursa
    // WebView çerezleri temizlenir ve her seferinde tekrar çözülür.
    private val cf by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/yeni-filmler/|$mainUrl/yeni-filmler/$PAGE_PLACEHOLDER" to "Yeni Filmler",
        "$mainUrl/en-cok-izlenen-filmler" to "En Çok İzlenenler",
        "$mainUrl/trend-filmler" to "Trend",
        "$mainUrl/filmizle/turkce-dublaj-filmler-1" to "Türkçe Dublaj",
        "$mainUrl/filmizle/turkce-altyazili-filmler-1" to "Türkçe Altyazılı",
        "$mainUrl/filmizle/dual-filmler-1" to "DUAL",
        "$mainUrl/filmizle/aksiyon-filmleri|$mainUrl/filmizle/aksiyon-filmleri/$PAGE_PLACEHOLDER" to "Aksiyon",
        "$mainUrl/filmizle/komedi-filmleri|$mainUrl/filmizle/komedi-filmleri/$PAGE_PLACEHOLDER" to "Komedi",
        "$mainUrl/filmizle/korku-filmleri|$mainUrl/filmizle/korku-filmleri/$PAGE_PLACEHOLDER" to "Korku",
        "$mainUrl/filmizle/dram-filmler-izle|$mainUrl/filmizle/dram-filmler-izle/$PAGE_PLACEHOLDER" to "Dram",
        "$mainUrl/filmizle/bilim-kurgu-filmleri|$mainUrl/filmizle/bilim-kurgu-filmleri/$PAGE_PLACEHOLDER" to "Bilim Kurgu",
        "$mainUrl/filmizle/romantik-filmler|$mainUrl/filmizle/romantik-filmler/$PAGE_PLACEHOLDER" to "Romantik",
        "$mainUrl/filmizle/yerli-filmler|$mainUrl/filmizle/yerli-filmler/$PAGE_PLACEHOLDER" to "Yerli",
        "$mainUrl/filmizle/yabanci-filmler|$mainUrl/filmizle/yabanci-filmler/$PAGE_PLACEHOLDER" to "Yabancı",
        "$mainUrl/filmizle/4k-filmler|$mainUrl/filmizle/4k-filmler/$PAGE_PLACEHOLDER" to "4K",
    )

    /**
     * Liste adresi "temel|sayfalı" biçiminde tutulur: `|` yoksa yalnızca ilk sayfa
     * vardır. Sayfalı biçimde `{page}` yerine sayfa numarası yazılır.
     */
    private fun pageUrl(data: String, page: Int): String? {
        val parts = data.split("|", limit = 2)
        if (parts.size == 1) return parts[0].takeIf { page <= 1 }
        return if (page <= 1) parts[0] else parts[1].replace(PAGE_PLACEHOLDER, page.toString())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
            ?: return newHomePageResponse(request.name, emptyList(), false)

        val document = app.get(url, headers = requestHeaders, interceptor = cf).document
        val items = parseList(document)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama/${query.trim().replace(" ", "+")}"
        return parseList(app.get(url, headers = requestHeaders, interceptor = cf).document)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = requestHeaders, interceptor = cf).document
        val jsonLd = document.jsonLd()

        val title = jsonLd?.get("name")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }

        val posterUrl = jsonLd?.get("image")?.imageUrl()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val plot = jsonLd?.get("description")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val year = jsonLd?.get("datePublished")?.asText()?.take(4)?.toIntOrNull()
        val tags = jsonLd?.get("genre")?.stringList()
            ?: document.select("a[href*='/filmizle/']").map { it.text().trim() }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }

        val actors = jsonLd?.get("actor")?.stringList("name") ?: emptyList()
        val rating = jsonLd?.get("aggregateRating")?.get("ratingValue")
            ?.asText()?.toDoubleOrNull()?.let { Score.from10(it) }
        val duration = jsonLd?.get("duration")?.asText()?.durationSeconds()
        val trailerUrl = jsonLd?.get("trailer")?.get("embedUrl")?.asText()
            ?: jsonLd?.get("trailer")?.get("url")?.asText()

        val recommendations = parseList(document)
            .filter { it.url.trimEnd('/') != url.trimEnd('/') }
            .take(24)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = rating
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
            if (!trailerUrl.isNullOrBlank()) addTrailer(trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data, headers = requestHeaders, interceptor = cf).text

        val sources = LinkedHashSet<String>()

        // 1) Gömülü `var scx = {...}` kaynakları
        extractScx(html)?.let { scx ->
            for (entry in scx.fields()) {
                val sx = entry.value.get("sx") ?: continue
                for (type in sx.fields()) {
                    for (node in type.value) {
                        decodeScx(node.asText())?.let { sources.add(it) }
                    }
                }
            }
        }

        // 2) Sayfada doğrudan bulunan oynatıcı iframe'leri (yedek)
        val document = org.jsoup.Jsoup.parse(html)
        for (iframe in document.select("iframe[data-src], iframe[src]")) {
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            normalizeSource(src)?.let { sources.add(it) }
        }

        var found = false
        for (sourceUrl in sources) {
            val ok = when {
                sourceUrl.contains("youtube.com", true) || sourceUrl.contains("youtu.be", true) -> false
                sourceUrl.contains(".m3u8", true) || sourceUrl.contains(".mp4", true) -> {
                    addLink(sourceUrl, sourceUrl, callback)
                    true
                }

                else -> resolveEmbed(sourceUrl, data, callback, subtitleCallback)
            }
            if (ok) found = true
        }

        return found
    }

    // ---------------------------------------------------------------------
    // Oynatıcı çözümleme
    // ---------------------------------------------------------------------

    private suspend fun resolveEmbed(
        embedUrl: String,
        filmUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(
                embedUrl,
                headers = requestHeaders + mapOf("Referer" to filmUrl),
                interceptor = cf,
            ).text
        }.getOrNull() ?: return false

        var streamUrl: String? = null

        // rapidvid.org: jwSetup.sources[0].file = av('...')
        val avRaw = Regex("""["']file["']\s*:\s*av\(\s*["']([^"']+)["']\s*\)""")
            .find(html)?.groupValues?.get(1)
        if (avRaw != null) streamUrl = decodeRapidVid(avRaw)

        // Genel yedek: sayfada açık bir m3u8 / master adresi varsa
        if (streamUrl == null) {
            streamUrl = Regex("""https?://[^\s"'<>]+?(?:master\.txt|\.m3u8)[^\s"'<>]*""")
                .find(html)?.value
        }

        if (!streamUrl.isNullOrBlank()) {
            addLink(streamUrl!!, embedUrl, callback)
        }

        addSubtitles(html, embedUrl, subtitleCallback)
        return !streamUrl.isNullOrBlank()
    }

    private suspend fun addLink(streamUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }

    private suspend fun addSubtitles(
        html: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val tracksMatch = Regex("""jwSetup\.tracks\s*=\s*(\[[\s\S]*?\])""").find(html)
            ?: Regex("""tracks\s*:\s*(\[[\s\S]*?\])""").find(html)
            ?: return
        val tracks = runCatching { mapper.readTree(tracksMatch.groupValues[1]) }.getOrNull()
            ?: return

        for (track in tracks) {
            val file = track.get("file")?.asText()?.takeIf { it.isNotBlank() } ?: continue
            val label = track.get("label")?.asText()?.trim().orEmpty()
            subtitleCallback.invoke(
                newSubtitleFile(subtitleLang(label), file) {
                    this.headers = subtitleHeaders(file, referer)
                }
            )
        }
    }

    // ---------------------------------------------------------------------
    // Kod çözücüler
    // ---------------------------------------------------------------------

    /** `var scx = {...};` nesnesini çıkarır. */
    private fun extractScx(html: String): JsonNode? {
        val match = Regex("""var\s+scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return runCatching { mapper.readTree(match.groupValues[1]) }.getOrNull()
    }

    /** scx değeri: ROT13 -> Base64. Sonuç adres veya `<iframe>` kodu olabilir. */
    private fun decodeScx(value: String): String? {
        val decoded = runCatching {
            String(Base64.decode(value.rot13(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.trim() ?: return null
        return normalizeSource(decoded)
    }

    /** `<iframe src="...">` içinden adresi çıkarır, YouTube'u eler. */
    private fun normalizeSource(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val iframeSrc = Regex("""src\s*=\s*["']([^"']+)["']""").find(value)?.groupValues?.get(1)
        val url = (iframeSrc ?: value).trim()
        if (!url.startsWith("http", true)) return null
        if (url.contains("youtube.com", true) || url.contains("youtu.be", true)) return null
        return url
    }

    /**
     * rapidvid.org oynatıcısındaki `av()` fonksiyonunu taklit eder:
     * ters çevir -> base64 -> her karakterden "K9L"[i%3] % 5 + 1 çıkar -> base64.
     */
    private fun decodeRapidVid(encoded: String): String? = runCatching {
        val step1 = String(
            Base64.decode(encoded.reversed(), Base64.DEFAULT),
            Charsets.ISO_8859_1,
        )
        val key = "K9L"
        val step2 = buildString(step1.length) {
            for (i in step1.indices) {
                append((step1[i].code - (key[i % 3].code % 5 + 1)).toChar())
            }
        }
        String(Base64.decode(step2, Base64.DEFAULT), Charsets.ISO_8859_1)
    }.getOrNull()

    private fun String.rot13(): String = buildString(length) {
        for (c in this@rot13) {
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + ((c - 'a' + 13) % 26)
                    in 'A'..'Z' -> 'A' + ((c - 'A' + 13) % 26)
                    else -> c
                }
            )
        }
    }

    // ---------------------------------------------------------------------
    // Liste kartları
    // ---------------------------------------------------------------------

    private fun parseList(document: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        // Yeni film listeleri: <li class="film">
        for (element in document.select("li.film")) {
            val anchor = element.selectFirst("a.tt") ?: element.selectFirst("a[href*='/film/']")
                ?: continue
            val href = fixUrlNull(anchor.attr("href")) ?: continue
            val title = element.selectFirst(".film-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.text().removeSuffix(" izle").trim().takeIf { it.isNotBlank() }
                ?: continue
            val image = element.selectFirst("img")
            val poster = fixUrlNull(
                image?.attr("data-src")?.ifBlank { image.attr("src") }
            )
            val year = element.selectFirst(".film-yil")?.text()?.trim()?.toIntOrNull()
            val score = element.selectFirst(".imdb")?.text()?.trim()?.toDoubleOrNull()

            items.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = score?.let { Score.from10(it) }
                }
            )
        }

        // Ana sayfa / arama kartları: <a class="fut-item">
        for (element in document.select("a.fut-item")) {
            val href = fixUrlNull(element.attr("href")) ?: continue
            val image = element.selectFirst("img")
            val title = element.selectFirst(".fut-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: continue
            val poster = fixUrlNull(
                image?.attr("data-src")?.ifBlank { image.attr("src") }
            )

            items.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }

        return items.distinctBy { it.url }
    }

    // ---------------------------------------------------------------------
    // Yardımcılar
    // ---------------------------------------------------------------------

    private fun Document.jsonLd(): JsonNode? {
        for (script in select("script[type='application/ld+json']")) {
            val root = runCatching { mapper.readTree(script.data()) }.getOrNull() ?: continue
            val graph = root.get("@graph")
            val nodes = if (graph != null && graph.isArray) graph.toList() else listOf(root)
            nodes.firstOrNull { it.get("@type")?.asText() in setOf("Movie", "TVSeries") }
                ?.let { return it }
        }
        return null
    }

    private fun JsonNode.imageUrl(): String? = when {
        isTextual -> asText().takeIf { it.isNotBlank() }
        isArray -> firstOrNull()?.imageUrl()
        isObject -> get("url")?.asText()?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun JsonNode.stringList(field: String? = null): List<String>? {
        val node = if (field == null) this else get(field) ?: return null
        val values = when {
            node.isArray -> node.mapNotNull { if (field == null) it.asText() else it.get(field)?.asText() }
            node.isTextual -> listOf(node.asText())
            node.isObject && field != null -> listOfNotNull(node.get(field)?.asText())
            else -> emptyList()
        }
        return values.map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    }

    private fun String.durationSeconds(): Int? {
        val match = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(this) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val seconds = match.groupValues[3].toIntOrNull() ?: 0
        return (hours * 3600 + minutes * 60 + seconds).takeIf { it > 0 }
    }

    private fun subtitleLang(label: String): String = when {
        label.contains("türk", true) || label.contains("turk", true) -> "tr"
        label.contains("ingiliz", true) || label.contains("english", true) -> "en"
        label.contains("alman", true) || label.contains("german", true) -> "de"
        label.contains("frans", true) || label.contains("french", true) -> "fr"
        label.contains("ispanyol", true) || label.contains("spanish", true) -> "es"
        label.contains("portekiz", true) || label.contains("portuguese", true) -> "pt"
        label.isBlank() -> "tr"
        else -> label.lowercase().take(5)
    }

    /** Cloudflare korumalı altyazı sunucuları için WebView çerezini de ekler. */
    private fun subtitleHeaders(url: String, referer: String): Map<String, String> {
        val headers = mutableMapOf(
            "Referer" to referer,
            "User-Agent" to USER_AGENT,
        )
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Cookie"] = it }
        return headers
    }
}
