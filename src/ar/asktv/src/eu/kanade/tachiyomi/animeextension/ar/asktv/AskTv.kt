package eu.kanade.tachiyomi.animeextension.ar.asktv

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animeextension.ar.asktv.dto.EpisodeData
import eu.kanade.tachiyomi.animeextension.ar.asktv.dto.Server
import eu.kanade.tachiyomi.animeextension.ar.asktv.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AskTv: ParsedAnimeHttpSource() {

    override val name = "قصة عشق"

    override val baseUrl = "https://arab3sk.net"

    override val lang = "ar"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply{
            thumbnail_url = element.select(".imgSer").attr("style")
                .substringAfter("url(").substringBefore(")").replace("large","long")
            title = element.select(".title").text()
            setUrlWithoutDomain(element.select("a").attr("href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.navigation div.pagination a:contains(›)"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/all-series/page/$page/")

    override fun popularAnimeSelector(): String = "article.postEp"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            name = element.select(".title").text()
            episode_number = element.select(".episodeNum").text().filter { it.isDigit() }.toFloat()
            val url = element.select("a").attr("href")
                .substringAfter("url=").replace("%3D","=")
            setUrlWithoutDomain(url.decodeBase64())
        }
    }
    override fun episodeListSelector(): String = popularAnimeSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val infoCard = document.select(".singleSeries")
            title = infoCard.select(".info h1").text()
            description = infoCard.select(".info .story").text()
            artist = infoCard.select(".info .tax a").joinToString(", ") { it.attr("title") }
            status = if("الأخيرة" in document.body().text()) SAnime.COMPLETED else SAnime.ONGOING
        }
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoListSelector(): String {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeData = response.asJsoup().select(".getEmbed a").attr("href").substringAfter("post=")
        val jsonData = json.decodeFromString<EpisodeData>(episodeData.decodeBase64())
        return jsonData.servers.parallelMap {
            runCatching { extractVideos(it) }.getOrElse { emptyList() }
        }.flatten()
    }
    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
    private fun extractVideos(server: Server): List<Video> {
        return when (server.name) {
            "ok" -> {
                OkruExtractor(client).videosFromUrl("https://www.ok.ru/videoembed/${server.id}")
            }
            "dailymotion" -> {
                DailymotionExtractor(client).videosFromUrl(server.id, headers)
            }
            "estream" -> {
                val request = client.newCall(GET("https://arabveturk.sbs/embed-${server.id}.html")).execute().asJsoup()
                val script = request.selectFirst("script:containsData(sources)")!!.data()
                val streamLink = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(script)!!.groupValues[1]
                val m3u8 = client.newCall(GET(streamLink)).execute().body.string()
                Regex("EXT-X-I-FRAME.*x(.*),URI=\"(.*)\"").findAll(m3u8).map {
                    Video(it.groupValues[2], "Estream: ${it.groupValues[1]}p", it.groupValues[2])
                }.toList()
            }
            "now" -> {
                VidBomExtractor(client).videosFromUrl("https://extreamnow.org/embed-${server.id}.html")
            }
            "Red HD" -> {
                StreamSBExtractor(client).videosFromUrl("https://www.sbbrisk.com/e/${server.id}", headers)
            }
            "Pro HD" -> {
                val request = client.newCall(GET("https://segavid.com/embed-${server.id}.html", headers)).execute().asJsoup()
                val data = JsUnpacker.unpackAndCombine(request.selectFirst("script:containsData(sources)")!!.data())!!
                val m3u8 = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(data)!!.groupValues[1]
                val qualities = data.substringAfter("qualityLabels").substringBefore("}")
                val qRegex = Regex("\".*?\"\\s*:\\s*\"(.*?)\"").find(qualities)!!
                Video(m3u8, qRegex.groupValues[1], m3u8).let(::listOf)
            }
            else -> null
        } ?: emptyList()
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply{
            thumbnail_url = element.select(".imgBg").attr("style")
                .substringAfter("url(").substringBefore(")")
            title = element.select(".title").text()
            setUrlWithoutDomain(element.select("a").attr("href"))
        }
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/$query/page/$page/")

    override fun searchAnimeSelector(): String = latestUpdatesSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply{
            thumbnail_url = element.select(".imgBg").attr("style")
                .substringAfter("url(").substringBefore(")")
            title = element.select(".title").text()
            val url = element.select("a").attr("href")
                .substringAfter("url=").replace("%3D","=")
            setUrlWithoutDomain(url.decodeBase64())
        }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodes/page/$page/")

    override fun latestUpdatesSelector(): String = "article.post"
}
