package com.dizigom

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Dizigom (https://www.dizigom.news) provider.
 *
 * Site yapısı:
 *  - Film sayfası:   /film/<slug>
 *  - Dizi sayfası:   /diziler/<slug>/            (sezonlar: /diziler/<slug>/?sezon=N)
 *  - Anime sayfası:  /anime/<slug>               (sezonlar: /anime/<slug>?sezon=N)
 *  - Bölüm sayfası:  /<dizi>-<s>-sezon-<b>-bolum/  veya  /anime/<slug>/sezon-<s>/bolum-<b>
 *  - Arama:          /ara?q=<sorgu>
 *
 * Oynatıcı, sayfaya <div data-pv="SLUG"> olarak gömülür. Gerçek adres
 * "core.js" tarafından <oynatıcı>/assets/js/s.php?s=SLUG adresinden yüklenir ve
 * oradan da window.__PLAYER__ içindeki m3u8 adresi alınır.
 */
class DizigomProvider : MainAPI() {
    override var mainUrl = "https://www.dizigom.news"
    override var name = "Dizigom"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        private const val PAGE_PLACEHOLDER = "{page}"
        private const val PLAYER_SCRIPT_PATH = "/assets/js/core.js"

        private val requestHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )

        private val mapper by lazy { jacksonObjectMapper() }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trend" to "Trend",
        "$mainUrl/dizi-izle/page/$PAGE_PLACEHOLDER/" to "Diziler",
        "$mainUrl/filmler?page=$PAGE_PLACEHOLDER" to "Filmler",
        "$mainUrl/animes" to "Animeler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        // Trend ve Animeler sayfalanmıyor; sadece ilk sayfaları var.
        if (page > 1 && !request.data.contains(PAGE_PLACEHOLDER)) {
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val url = request.data.replace(PAGE_PLACEHOLDER, page.toString())
        val document = app.get(url, headers = requestHeaders).document
        val items = document.select("a.group.block")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/ara",
            headers = requestHeaders,
            params = mapOf("q" to query),
        ).document
            .select("a.group.block")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = requestHeaders).document
        val jsonLd = document.jsonLd()

        val title = jsonLd?.get("name")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Başlık bulunamadı: $url")

        val posterUrl = jsonLd?.get("image")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val plot = jsonLd?.get("description")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val year = (jsonLd?.get("datePublished") ?: jsonLd?.get("startDate"))
            ?.asText()?.take(4)?.toIntOrNull()

        val tags = jsonLd?.get("genre")
            ?.mapNotNull { it.asText().takeIf { tag -> tag.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() }
            ?: document.select("a.rounded-badge[href*='/tur/']").map { it.text() }

        val actors = jsonLd?.get("actor")
            ?.mapNotNull { it.get("name")?.asText()?.takeIf { name -> name.isNotBlank() } }
            ?: emptyList()

        val rating = jsonLd?.get("aggregateRating")?.get("ratingValue")
            ?.asDouble()?.let { Score.from10(it) }

        val trailerUrl = jsonLd?.get("trailer")?.get("embedUrl")?.asText()
        val duration = jsonLd?.get("duration")?.asText()?.toDurationSeconds()
        val showStatus = document.showStatus()

        val recommendations = document.select("a.group.block")
            .mapNotNull { it.toSearchResponse() }
            .filter { it.url.trimEnd('/') != url.trimEnd('/') }
            .distinctBy { it.url }
            .take(24)

        val isMovie = url.contains("/film/")
        val type = when {
            isMovie -> TvType.Movie
            url.contains("/anime/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }

        val episodes = document.loadEpisodes(url)

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = rating
            this.showStatus = showStatus
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = requestHeaders).document

        // Oynatıcı tipi: "embed" ise oynatıcı gömülüdür,
        // "none" ise sitede kaynak yoktur (yalnızca fragman gösterilir).
        val playerType = document.selectFirst("[data-player]")
            ?.attr("data-player-type")?.trim().orEmpty()

        // <div data-pv="SLUG"> — oynatıcının opak kimliği
        val slug = document.selectFirst("div[data-pv]")
            ?.attr("data-pv")?.trim()?.takeIf { it.isNotBlank() }

        if (slug == null) {
            // Cloudstream'e boş liste yerine anlaşılır bir mesaj göster.
            throw ErrorLoadingException(
                if (playerType == "none") {
                    "Sitede bu film için oynatılabilir kaynak yok; yalnızca fragman bulunuyor."
                } else {
                    "Oynatıcı bu sayfada bulunamadı (site yapısı değişmiş olabilir)."
                }
            )
        }

        // <script src="https://<oynatıcı>/assets/js/core.js">
        val scriptSrc = document.selectFirst("script[src*=core.js]")
            ?.attr("src")?.trim().orEmpty()
        val playerBase = scriptSrc.substringBefore(PLAYER_SCRIPT_PATH).ifBlank {
            "https://play2.pilavyerplay.top"
        }

        // s.php, sayfanın kendi origin'inden çağrılmayı bekliyor (Referer kontrolü).
        val playerHtml = app.get(
            "$playerBase/assets/js/s.php",
            headers = requestHeaders + mapOf("Referer" to data),
            params = mapOf("s" to slug),
        ).text

        val playerJson = Regex(
            """window\.__PLAYER__\s*=\s*(\{.*?\});\s*</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(playerHtml)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Oynatıcı yanıtı okunamadı.")

        val player = runCatching { mapper.readTree(playerJson) }.getOrNull()
            ?: throw ErrorLoadingException("Oynatıcı verisi çözülemedi.")

        val streamUrl = player.get("stream")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Oynatıcı akış adresi vermedi.")

        val playerReferer = "$playerBase/"

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = playerReferer
                this.headers = mapOf(
                    "Referer" to playerReferer,
                    "User-Agent" to USER_AGENT,
                )
            }
        )

        // Altyazılar (VTT) doğrudan oynatıcının API'sinden geliyor.
        player.get("subs")?.forEach { subtitle ->
            val subtitleUrl = subtitle.get("src")?.asText()
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            val subtitleLang = subtitle.get("lang")?.asText()
                ?.takeIf { it.isNotBlank() } ?: "tr"

            subtitleCallback.invoke(
                newSubtitleFile(subtitleLang, subtitleUrl) {
                    this.headers = mapOf(
                        "Referer" to playerReferer,
                        "User-Agent" to USER_AGENT,
                    )
                }
            )
        }

        return true
    }

    // ---------------------------------------------------------------------
    // Arama sonuçları / ana sayfa kartları
    // ---------------------------------------------------------------------
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null

        val type = when {
            href.contains("/film/") -> TvType.Movie
            href.contains("/anime/") -> TvType.Anime
            href.contains("/diziler/") -> TvType.TvSeries
            else -> return null
        }

        val image = selectFirst("img")
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: image?.attr("alt")
                ?.removeSuffix(" Film izle")
                ?.removeSuffix(" Dizi izle")
                ?.removeSuffix(" Anime izle")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(image?.attr("src") ?: image?.attr("data-src"))
        val year = select("p").lastOrNull()?.text()?.trim()?.toIntOrNull()
        val score = selectFirst(".badge-rating")?.text()?.trim()
            ?.toDoubleOrNull()?.let { Score.from10(it) }

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
            }

            TvType.Anime -> newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
            }

            else -> newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
            }
        }
    }

    // ---------------------------------------------------------------------
    // Bölümler
    // ---------------------------------------------------------------------
    private suspend fun Document.loadEpisodes(url: String): List<Episode> {
        val seasons = select("#bolumler a[href*='sezon=']")
            .mapNotNull { element ->
                Regex("""[?&]sezon=(\d+)""")
                    .find(element.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            .distinct()
            .sorted()

        // Sayfada sezon seçici yoksa bölümler zaten sayfadadır.
        if (seasons.isEmpty()) return parseEpisodeCards()

        val activeSeason = select("#bolumler a[href*='sezon=']")
            .firstOrNull { it.className().contains("bg-accent") }
            ?.let {
                Regex("""[?&]sezon=(\d+)""")
                    .find(it.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
            }

        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            val seasonDocument = if (season == activeSeason) {
                this
            } else {
                app.get(
                    "${url.substringBefore("?")}?sezon=$season",
                    headers = requestHeaders,
                ).document
            }
            episodes.addAll(seasonDocument.parseEpisodeCards())
        }

        return episodes.distinctBy { it.data }.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: 0 })
        )
    }

    private fun Document.parseEpisodeCards(): List<Episode> {
        return select("#bolumler a.group[href]").mapNotNull { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null

            val numbers = Regex("""(\d+)-sezon-(\d+)-bolum""").find(href)
                ?: Regex("""sezon-(\d+)/bolum-(\d+)""").find(href)
                ?: return@mapNotNull null

            val season = numbers.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val episodeNumber = numbers.groupValues[2].toIntOrNull() ?: return@mapNotNull null

            val paragraphs = element.select("p")
            val label = paragraphs.lastOrNull()?.text()?.trim()
            val episodeTitle = paragraphs.firstOrNull()?.text()?.trim()
                ?.takeIf { it.isNotBlank() && it != label }

            newEpisode(href) {
                this.name = episodeTitle
                this.season = season
                this.episode = episodeNumber
                this.posterUrl = fixUrlNull(element.selectFirst("img")?.attr("src"))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Yardımcılar
    // ---------------------------------------------------------------------

    /** Sayfadaki schema.org JSON-LD düğümünü (TVSeries veya Movie) döndürür. */
    private fun Document.jsonLd(): JsonNode? {
        for (script in select("script[type='application/ld+json']")) {
            val root = runCatching { mapper.readTree(script.data()) }.getOrNull() ?: continue
            val graph = root.get("@graph")
            val nodes = if (graph != null && graph.isArray) graph.toList() else listOf(root)

            nodes.firstOrNull {
                it.get("@type")?.asText() in setOf("TVSeries", "Movie")
            }?.let { return it }
        }
        return null
    }

    /** "Durum" satırından dizi durumunu çıkarır. */
    private fun Document.showStatus(): ShowStatus? {
        val statusText = select("dl > div").firstOrNull {
            it.selectFirst("dt")?.text()?.trim() == "Durum"
        }?.selectFirst("dd")?.text()?.trim() ?: return null

        return when {
            statusText.contains("Devam", ignoreCase = true) -> ShowStatus.Ongoing
            statusText.contains("Final", ignoreCase = true) -> ShowStatus.Completed
            statusText.contains("Bitti", ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }
    }

    /** ISO-8601 süresini ("PT109M") saniyeye çevirir. */
    private fun String.toDurationSeconds(): Int? {
        val match = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(this)
            ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val seconds = match.groupValues[3].toIntOrNull() ?: 0
        val total = hours * 3600 + minutes * 60 + seconds
        return total.takeIf { it > 0 }
    }
}
