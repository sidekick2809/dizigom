// FullHDFilmizlesene extension for Cloudstream
version = 1

android {
    namespace = "com.fullhdfilmizlesene"
}

cloudstream {
    description = "FullHDFilmizlesene - Türkçe dublaj/altyazılı full HD film kaynağı (fullhdfilmizlesene.now)"
    authors = listOf("sidekick2809")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie")
    language = "tr"

    iconUrl = "https://www.fullhdfilmizlesene.now/favicon.ico"
}
