// Hdfilmcehennemi extension for Cloudstream
version = 3

android {
    namespace = "com.hdfilmcehennemi"
}

cloudstream {
    description = "Hdfilmcehennemi - Türkçe dublaj/altyazılı HD film kaynağı (hdfilmcehennemi.nl)"
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

    iconUrl = "https://www.hdfilmcehennemi.nl/favicon.ico"
}
