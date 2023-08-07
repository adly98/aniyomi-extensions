package eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class UQLoadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, host: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val check = document.selectFirst("script:containsData(sources)")!!.data()
        val videoUrl = check.substringAfter("sources: [\"").substringBefore("\"")
        val qualityHost = host.replaceFirstChar(Char::uppercase)
        return when{
            "sources" in check -> Video(videoUrl, "$qualityHost Mirror", videoUrl).let(::listOf)
            else -> emptyList()
        }
    }
}
