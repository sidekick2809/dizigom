// Filmmakinesi extension for Cloudstream
version = 3

android {
    namespace = "com.filmmakinesi"
}

cloudstream {
    description = "Filmmakinesi - Türkçe dublaj/altyazılı 1080p film kaynağı (filmmakinesi.to)"
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

    iconUrl = "https://filmmakinesi.to/favicon.ico"
}
