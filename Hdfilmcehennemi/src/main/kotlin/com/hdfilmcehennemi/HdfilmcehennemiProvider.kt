package com.hdfilmcehennemi

import android.util.Base64
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Hdfilmcehennemi (https://www.hdfilmcehennemi.nl) sağlayıcısı.
 *
 * Akış:
 *  - Film sayfası: /<slug>-<id>/  (ör. /the-odyssey-3/)
 *  - Arama:        /search?q=<sorgu>  (JSON döner: {"results": ["<a ...>", ...]})
 *  - Liste:        /category/film-izle-2/ , /dil/... , /tur/...
 *
 * Film sayfasındaki <iframe data-src="https://<embed>/video/embed/<id>/?...">
 * oynatıcısı, "autocdn" şablonunu kullanır. Kaynak adresi sayfaya gömülü
 * `var <ad> = <decoder>("..." .split("!"));` biçiminde kodlanmıştır ve çözüm
 * embed alan adına (hostname) bağlıdır.
 *
 * Alan adı değişirse yalnızca [mainUrl] güncellenmelidir (bkz. HAFIZA.md).
 */
class HdfilmcehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "Hdfilmcehennemi"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private val mapper by lazy { jacksonObjectMapper() }

        private val requestHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )
    }

    private val cf by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/category/film-izle-2/" to "Filmler",
        "$mainUrl/dil/turkce-dublajli-film-izleyin-7/" to "Türkçe Dublaj",
        "$mainUrl/dil/turkce-altyazili-filmleri-izleme-sitesi-4/" to "Türkçe Altyazılı",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-9/" to "Aksiyon",
        "$mainUrl/tur/komedi-filmlerini-izleyin-3/" to "Komedi",
        "$mainUrl/tur/korku-filmlerini-izle-12/" to "Korku",
        "$mainUrl/tur/dram-filmlerini-izle-4/" to "Dram",
        "$mainUrl/tur/bilim-kurgu-filmlerini-izleyin-9/" to "Bilim Kurgu",
        "$mainUrl/tur/gerilim-filmlerini-izle-5/" to "Gerilim",
        "$mainUrl/tur/romantik-filmleri-izle-4/" to "Romantik",
        "$mainUrl/tur/savas-filmleri-izle-6/" to "Savaş",
        "$mainUrl/category/1080p-hd-film-izle-6/" to "1080p",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val document = app.get(request.data, headers = requestHeaders, interceptor = cf).document
        val items = parseList(document)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search",
            headers = requestHeaders,
            params = mapOf("q" to query.trim()),
            interceptor = cf,
        )
        val root = runCatching { mapper.readTree(response.text) }.getOrNull() ?: return emptyList()
        val results = root.get("results") ?: return emptyList()

        return results.mapNotNull { node ->
            val anchor = Jsoup.parse(node.asText()).selectFirst("a.search-result")
                ?: Jsoup.parse(node.asText()).selectFirst("a[href]")
                ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val title = image?.attr("alt")?.substringBefore(" - ")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = fixUrlNull(image?.attr("src"))
            val year = anchor.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
            val score = anchor.selectFirst(".imdb")?.text()?.trim()?.toDoubleOrNull()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = requestHeaders, interceptor = cf).document
        val jsonLd = document.jsonLd()

        val title = jsonLd?.get("name")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: url.removeSuffix("/").substringAfterLast('/').replace('-', ' ')
                .replaceFirstChar { it.uppercase() }

        val posterUrl: String? = jsonLd?.get("image")?.imageUrl()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".poster img")?.run {
                fixUrlNull(attr("data-src").ifBlank { attr("src") })
            }

        val plot = jsonLd?.get("description")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val year = jsonLd?.get("datePublished")?.asText()?.take(4)?.toIntOrNull()
        val tags = jsonLd?.get("genre")?.stringList()
            ?: document.select("a[href*='/tur/']").map { it.text().trim() }
                .filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }

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
        val document = Jsoup.parse(html)

        val embeds = LinkedHashSet<String>()
        for (iframe in document.select("iframe[data-src], iframe[src]")) {
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }.trim()
            if (src.startsWith("http", true) && !src.contains("youtube", true)) {
                embeds.add(src)
            }
        }

        var found = false
        for (embedUrl in embeds) {
            if (resolveEmbed(embedUrl, data, callback, subtitleCallback)) found = true
        }

        // Yedek: sayfada doğrudan bir master/m3u8 adresi varsa
        if (!found) {
            val direct = Regex("""https?://[^\s"'<>]+?(?:master\.txt|\.m3u8)[^\s"'<>]*""")
                .find(html)?.value
            if (!direct.isNullOrBlank()) {
                addLink(direct!!, data, callback)
                found = true
            }
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

        val host = runCatching { java.net.URI(embedUrl).host }.getOrNull().orEmpty()

        // sources: [{file: VAR, type: "hls"}]  ve  var VAR = FN("..." .split("X"));
        val varName = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*([A-Za-z0-9_]+)""")
            .find(html)?.groupValues?.get(1)

        var streamUrl: String? = null
        if (varName != null) {
            val decl = Regex(
                """var\s+${Regex.escape(varName)}\s*=\s*([A-Za-z0-9_]+)\s*\(\s*["']([^"']+)["']\s*\.split\(\s*["'](.*?)["']\s*\)"""
            ).find(html)
            if (decl != null) {
                val fnName = decl.groupValues[1]
                val encoded = decl.groupValues[2]
                // Ayırıcı karakter sayfaya göre değişir ("!", "~", "@", "^" ...).
                val delimiter = decl.groupValues[3].ifEmpty { "!" }
                val rotBase = decoderRotBase(html, fnName)
                streamUrl = decodeAutoCdn(encoded, delimiter, rotBase, host)
            }
        }

        if (streamUrl.isNullOrBlank()) {
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
        val tracksMatch = Regex("""tracks\s*:\s*(\[[\s\S]*?\])""").find(html)
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
    // autocdn kod çözücü
    // ---------------------------------------------------------------------

    /**
     * "autocdn" oynatıcısının gömülü kod çözücüsünü taklit eder.
     * Algoritma embed alan adına (host) bağlıdır, bu yüzden [host] geçilmelidir.
     * [delimiter] sayfadaki `.split("X")` ifadesinden, [rotBase] ise çözücü
     * fonksiyonunun gövdesinden okunur (genelde 96).
     */
    private fun decodeAutoCdn(
        encoded: String,
        delimiter: String,
        rotBase: Int,
        host: String,
    ): String? {
        return runCatching {
            val parts = encoded.split(delimiter).toMutableList()
            if (parts.size < 10) return null

            val size = parts.size - 2
            val pvqo = size % 7
            val h4k = 8 + (size % 5)
            if (h4k >= parts.size || pvqo >= parts.size) return null

            val sb5 = parts.removeAt(h4k)
            val ff8 = parts.removeAt(pvqo) + host
            var za4 = parts.joinToString("")

            if (ff8.length > 4096) za4 = b64(za4)

            var hz = 0
            var m = 0
            for (i in ff8.indices) {
                val p = ff8[i].code
                hz = (hz * 37 + p) % 241
                m = (m + ((p shl 1) xor i)) and 255
            }

            var y = (hz * 3 + m) % 256
            val q = (m % 11) + 5
            var o = ((m * 251 + hz) % 65519) + 1

            for (i in sb5.length - 1 downTo 0) {
                when (sb5[i]) {
                    '7' -> za4 = b64(za4)
                    '3' -> za4 = za4.reversed()
                    else -> {
                        val shift = (26 - ((sb5[i].code - rotBase) % 26)) % 26
                        za4 = rotShift(za4, shift)
                    }
                }
            }
            if (sb5.length > 2048) za4 = za4.reversed()

            val n = za4.length
            if (n == 0) return null
            val swaps = IntArray(n)
            for (i in n - 1 downTo 1) {
                o = (o * 97 + 41) % 65519
                swaps[i] = o % (i + 1)
            }
            val chars = za4.toCharArray()
            for (i in 1 until n) {
                val j = swaps[i]
                val t = chars[i]
                chars[i] = chars[j]
                chars[j] = t
            }

            var u = y
            val out = CharArray(n)
            for (i in 0 until n) {
                val p = chars[i].code
                u = (u * 5 + q) % 256
                out[i] = (p xor u).toChar()
                u = (u + p) % 256
            }
            String(out)
        }.getOrNull()
    }

    private fun b64(value: String): String =
        String(Base64.decode(value, Base64.DEFAULT), Charsets.ISO_8859_1)

    /**
     * Çözücü fonksiyonunun gövdesindeki harf kaydırma tabanını bulur.
     * Şablon `charCodeAt(0) - 96` (küçük harf) veya `- 64` (büyük harf) kullanır.
     */
    private fun decoderRotBase(html: String, fnName: String): Int {
        val start = Regex("""var\s+${Regex.escape(fnName)}\s*=\s*function""")
            .find(html)?.range?.first ?: return 96
        val body = html.substring(start, minOf(html.length, start + 4000))
        return when {
            body.contains("- 64)") -> 64
            else -> 96
        }
    }

    private fun rotShift(value: String, shift: Int): String {
        val s = ((shift % 26) + 26) % 26
        return buildString(value.length) {
            for (c in value) {
                append(
                    when (c) {
                        in 'A'..'Z' -> 'A' + ((c - 'A' + s) % 26)
                        in 'a'..'z' -> 'a' + ((c - 'a' + s) % 26)
                        else -> c
                    }
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Liste kartları
    // ---------------------------------------------------------------------

    private fun parseList(document: Document): List<SearchResponse> {
        return document.select("a.poster[href]").mapNotNull { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val image = element.selectFirst("img")
            val title = element.attr("title").trim().takeIf { it.isNotBlank() }
                ?: image?.attr("alt")?.removeSuffix(" izle")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = fixUrlNull(
                image?.attr("data-src")?.ifBlank { image.attr("src") }
            )
            val year = element.selectFirst(".poster-meta span")?.text()?.trim()?.toIntOrNull()
            val score = element.selectFirst(".poster-meta .imdb")?.text()?.trim()
                ?.toDoubleOrNull()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        }.distinctBy { it.url }
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
