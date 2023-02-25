package eu.kanade.tachiyomi.extension.en.setsuscans

import eu.kanade.tachiyomi.multisrc.madara.Madara
import okhttp3.OkHttpClient
// import eu.kanade.tachiyomi.network.interceptor.rateLimit
// import java.util.concurrent.TimeUnit

class SetsuScans : Madara("Setsu Scans", "https://setsuscans.com", "en") {
    override val client: OkHttpClient = super.client.newBuilder()
        // .rateLimit(1, 1, TimeUnit.SECONDS)
        .build()
}
