package eu.kanade.tachiyomi.lib.vidguardextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.JsonObject
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VidGuardExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun getMediaUrl(host: String, mediaId: String): List<Video> {
        val regex = Regex("(?://|\\.)(vid-?guard|vgfplay|fslinks|moflix-stream|listeamed|gsfjzmqu|v?[g6b]?embedv?\\.to|com|day|xyz|org|net|sbs)/[evd]/([0-9a-zA-Z]+)")
        val match = regex.find(host)!!
        val webUrl = getUrl(match.groupValues[1], match.groupValues[2])
        val html = client.newCall(GET(webUrl, headers)).execute().body.string()

        val r = Regex("""eval\("window\.ADBLOCKER\s*=\s*false;\\n(.+?);"\);</script""").find(html)
        if (r != null) {
            val decodedString = r.groupValues[1].replace("\\u002b", "+")
                .replace("\\u0027", "'")
                .replace("\\u0022", "\"")
                .replace("\\/", "/")
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
            val aaDecoded = AADecoder().decode(decodedString ?: "", alt = true)
            val streamUrl = json.decodeFromString<Result>(aaDecoded.substring(11)).stream
            if (streamUrl.isNotEmpty()) {
                var finalStreamUrl = streamUrl
                if (!finalStreamUrl.startsWith("https://")) {
                    finalStreamUrl = finalStreamUrl.replace(":/*", "://")
                }
                finalStreamUrl = sigDecode(finalStreamUrl)
                return Video(finalStreamUrl, "VidGuard", finalStreamUrl).let(::listOf)
            }
        }
        return emptyList()
    }

    private fun getUrl(host: String, mediaId: String): String {
        val hosts = listOf("vidguard", "vid-guard", "vgfplay", "vgembed", "vembed.net", "embedv.net")
        val adjustedHost = if (hosts.any { it in host }) "listeamed.net" else host
        return "https://$adjustedHost/e/$mediaId"
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val t = StringBuilder()
        for (v in sig.chunked(2).map { it.toInt(16) }) {
            t.append((v.toChar().code xor 2).toChar())
        }
        val decoded = Base64.decode("$t==", Base64.DEFAULT).dropLast(5).reversed()
        val swapped = decoded.chunked(2).flatMap { it.reversed() }
        return url.replace(sig, swapped.joinToString("").dropLast(5))
    }
}
@Serializable
class Result (val stream: String)
