package eu.kanade.tachiyomi.animeextension.ar.arablionz

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ArabLionz: ParsedAnimeHttpSource() {

    override val name = "ArabLionz"

    override val baseUrl = "https://arlionztv.click"

    override val lang = "ar"

    override val supportsLatest = false

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val except = Regex("اطلب تجربتك|اشترك الأن|Mp3")
        val animeList = mutableListOf<SAnime>()
        document.select(popularAnimeSelector()).map{
            if (!it.select(".Box--Absolute").text().contains(except)) {
                animeList.add(SAnime.create().apply {
                    title = it.select("a").attr("title")
                    thumbnail_url = it.select(".imgInit").attr("data-image")
                    setUrlWithoutDomain(it.select("a").attr("href"))
                })
            }
        }
        return AnimesPage(animeList, !document.select(popularAnimeNextPageSelector()).isNullOrEmpty())
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        TODO("Not Used")
    }

    override fun popularAnimeNextPageSelector(): String = "div.paginate ul.page-numbers li:contains(»)"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/%D8%B9%D8%B1%D8%A8-%D9%84%D9%8A%D9%88%D9%86%D8%B2/page/$page/")

    override fun popularAnimeSelector(): String = "div.Grid--ArabLionz div.Posts--Single--Box"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        when{
            document.select(seasonListSelector()).isNotEmpty() -> {
                document.select(seasonListSelector()).forEach { season ->
                    val seasonTitle = season.select("span").text()
                    val newHeaders = headers.newBuilder()
                        .add("origin", baseUrl)
                        .add("referer", response.request.url.toString())
                        .add("x-requested-with", "XMLHttpRequest").build()
                    val seasonData = client.newCall(POST(season.attr("href"), headers = newHeaders)).execute().asJsoup()
                    episodes.addAll(seasonData.select("a").map{ episode ->
                        val seasonText = Regex("""(ا?ل?موسم\s.*)\s?""").find(seasonTitle)
                        SEpisode.create().apply {
                            name = "${seasonText!!.groupValues[1]} ${episode.text()}"
                            setUrlWithoutDomain(episode.attr("href"))
                            episode_number = episode.text().filter { it.isDigit() }.toFloat()
                        }
                    })
                }
            }
            document.select(episodeListSelector()).isNotEmpty() -> {
                document.select(episodeListSelector()).forEach {
                    episodes.add(episodeFromElement(it))
                }
            }
            else -> {
                episodes.add(SEpisode.create().apply {
                    name = "مشاهدة"
                    setUrlWithoutDomain(response.request.url.toString())
                })
            }
        }
        return episodes
    }
    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            name = element.text()
            setUrlWithoutDomain(element.attr("href"))
            episode_number = element.text().filter { it.isDigit() }.toFloat()
        }
    }

    private fun seasonListSelector(): String = "div.Setion--Title--mini ul li a"
    override fun episodeListSelector(): String = "div.Episode--Lists a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            description = document.select("p.Singular--Story-P").text()
            title = document.select("div.Pop--Actors:contains(الاسم الاصلى) ul li").text()
            genre = document.select("div.Singular--Geners ul li a").joinToString(", "){ it.text() }
            author = document.select("div.Pop--Actors:contains(الدولة) ul li").text()
            status = SAnime.UNKNOWN
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        fun videosFromUrl(url: String, host: String): List<Video> {
            val doc = client.newCall(GET(url)).execute().asJsoup()
            val script = doc.selectFirst("script:containsData(sources)")!!
            val data = script.data().substringAfter("sources: [").substringBefore("],")
            return data.split("file:\"").drop(1).map { source ->
                val src = source.substringBefore("\"")
                val qualityHost = host.replaceFirstChar(Char::uppercase)
                var quality = source.substringAfter("label:\"").substringBefore("\"")
                if (quality.length > 15) { quality = "480p" }
                Video(src, "$qualityHost: $quality", src)
            }
        }
        val watchId = response.asJsoup().select("div.WatchBtn").attr("data-id")
        val newHeaders = headers.newBuilder()
            .add("origin", baseUrl)
            .add("referer", response.request.url.toString())
            .add("x-requested-with", "XMLHttpRequest").build()
        val watchServers = client.newCall(POST("$baseUrl/PostServersWatch/$watchId", headers = newHeaders)).execute().asJsoup()
        return watchServers.select(videoListSelector()).parallelMap {
            runCatching {
                val urlIndex = it.attr("data-i")
                val watchUrl = client.newCall(POST("$baseUrl/Embedder/$watchId/$urlIndex", headers = newHeaders)).execute().asJsoup()
                val iframeUrl = watchUrl.select("iframe").attr("src")
                when{
                    VIDBOM_REGEX.containsMatchIn(iframeUrl) -> videosFromUrl(iframeUrl, VIDBOM_REGEX.find(iframeUrl)!!.groupValues[1])
                    "ok" in iframeUrl -> OkruExtractor(client).videosFromUrl(iframeUrl)
                    "mixdrop" in iframeUrl -> MixDropExtractor(client).videoFromUrl(iframeUrl)
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
        }.flatten()
    }
    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoListSelector(): String = "ul li"

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        TODO("Not yet implemented")
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search/$query/page/$page/")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesNextPageSelector(): String? {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesRequest(page: Int): Request {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesSelector(): String {
        TODO("Not yet implemented")
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
    companion object {
        private val VIDBOM_REGEX = Regex("(v[aie]d[bp][aoe]?m|myvii?d|govad|segavid|v[aei]{1,2}dshar[er]?)\\.(?:com|net|org|xyz)(?::\\d+)?/(?:embed[/-])?([A-Za-z0-9]+).html")
    }
}