// Dizigom extension for Cloudstream
version = 1

cloudstream {
    description = "Dizigom - Türkçe dizi, film ve anime kaynağı (dizigom.news)"
    authors = listOf("mehmet")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "tr"

    iconUrl = "https://www.dizigom.news/apple-touch-icon.png"
}
