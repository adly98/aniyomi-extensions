package eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamWishExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String,host: String, headers: Headers): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")!!.data()
        val scriptData = JsUnpacker.unpackAndCombine(script)!!
        val m3u8 = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(scriptData)!!.groupValues[1]
        val qualities = scriptData.substringAfter("qualityLabels").substringBefore("}")
        val qRegex = Regex("\".*?\":\\s*\"(.*?)\"").findAll(qualities)
        val qualityHost = host.replaceFirstChar(Char::uppercase)
        return qRegex.map {
            val quality = "$qualityHost: " + it.groupValues[1]
            Video(m3u8, quality, m3u8, headers)
        }.toList()
    }
}
