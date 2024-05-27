package eu.kanade.tachiyomi.lib.vidbomextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidBomExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, private val headers: Headers): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")!!
        val data = script.data().substringAfter("sources: [").substringBefore("],")

        return data.split("file:\"").drop(1).map { source ->
            val src = source.substringBefore("\"")
            var quality = "Vidbom: " + source.substringAfter("label:\"").substringBefore("\"")
            if (quality.length > 15) {
                quality = "Vidshare: 480p"
            }
            Video(src, quality, src)
        }
    }
}
