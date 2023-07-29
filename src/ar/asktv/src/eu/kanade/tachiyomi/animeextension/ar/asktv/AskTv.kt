package eu.kanade.tachiyomi.animeextension.ar.asktv

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animeextension.ar.asktv.dto.EpisodeData
import eu.kanade.tachiyomi.animeextension.ar.asktv.dto.Server
import eu.kanade.tachiyomi.animeextension.ar.asktv.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AskTv: ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "قصة عشق"

    override val baseUrl by lazy { getPrefHostUrl(preferences) }

    override val lang = "ar"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
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
    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")
    override fun episodeListSelector(): String = "article.postEp"
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        fun addEpisodeNew(url: String, title: String, num: Float = 1F) {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(url)
            episode.name = title
            episode.episode_number = num
            episodes.add(episode)
        }

        fun addEpisodes(response: Response) {
            val document = response.asJsoup()
            val url = response.request.url.toString()
            if(document.select(episodeListSelector()).isNullOrEmpty()){
                addEpisodeNew(url, "مشاهدة")
            } else {
                document.select(episodeListSelector()).forEach{ episode ->
                    addEpisodeNew(
                        episode.select("a").attr("href").substringAfter("url=").replace("%3D","="),
                        episode.select("a").attr("title").replace(" - قصة عشق",""),
                        episode.select(".episodeNum").text().filter { it.isDigit() }.toFloat()
                    )
                }
            }
        }

        addEpisodes(response)

        return episodes
    }
    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val infoCard = document.select(".singleSeries")
            title = infoCard.select(".info h1").text()
            description = infoCard.select(".info .story").text()
            artist = infoCard.select(".info .tax a").joinToString(", ") { it.attr("title") }
            status = if("الأخيرة" in document.body().text() || "فيلم" in infoCard.select("h1").text()) SAnime.COMPLETED else SAnime.ONGOING
        }
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video  = throw Exception("not used")

    override fun videoListSelector(): String  = throw Exception("not used")

    override fun videoUrlParse(document: Document): String  = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
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
                Regex("#EXT-X-STREAM.*?x(.*?),.*\\n(.+)").findAll(m3u8).map {
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
                Video(m3u8, "Pro HD: " + qRegex.groupValues[1], m3u8).let(::listOf)
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
            val url = element.select("a").attr("href")
            setUrlWithoutDomain(if("url=" in url) url.substringAfter("url=").decodeBase64() else url)
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
    // =============================== Preference ===============================
    private fun getPrefHostUrl(preferences: SharedPreferences): String = preferences.getString(
        "default_domain",
        "https://arab3sk.net",
    )!!.trim()
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val defaultDomain = EditTextPreference(screen.context).apply {
            key = "default_domain"
            title = "Enter default domain"
            summary = getPrefHostUrl(preferences)
            this.setDefaultValue(getPrefHostUrl(preferences))
            dialogTitle = "Default domain"
            dialogMessage = "You can change the site domain from here"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString("default_domain", newValue as String).commit()
                    Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(defaultDomain)
        screen.addPreference(videoQualityPref)
    }
}
