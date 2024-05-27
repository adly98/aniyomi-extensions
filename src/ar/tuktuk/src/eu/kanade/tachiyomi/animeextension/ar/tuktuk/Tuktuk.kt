package eu.kanade.tachiyomi.animeextension.ar.tuktuk

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Tuktuk : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "توك توك سينما"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://w.tuktokcinema.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // ============================ popular ===============================

    override fun popularAnimeSelector(): String = "div.Block--Item, div.Small--Box"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("a").attr("title"), true).trim()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href") + "watch/")
        return anime
    }

    private fun titleEdit(title: String, details: Boolean = false): String {
        return if (Regex("(?:فيلم|عرض)\\s(.*\\s[0-9]+)\\s(.+?)\\s").containsMatchIn(title)) {
            val titleGroup = Regex("(?:فيلم|عرض)\\s(.*\\s[0-9]+)\\s(.+?)\\s").find(title)
            val movieName = titleGroup!!.groupValues[1]
            val type = titleGroup.groupValues[2]
            movieName + if (details) " ($type)" else ""
        } else if (Regex("(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)").containsMatchIn(title)) {
            val titleGroup = Regex("(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)").find(title)
            val seriesName = titleGroup!!.groupValues[1]
            val epNum = titleGroup.groupValues[2]
            if (details) {
                "$seriesName (ep:$epNum)"
            } else if (seriesName.contains("الموسم")) {
                seriesName.split("الموسم")[0].trim()
            } else {
                seriesName
            }
        } else {
            title
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    // ============================ episodes ===============================

    private fun seasonsNextPageSelector() = "section.allseasonss div.Block--Item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodeNew(url: String, title: String) {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(url)
            episode.name = title
            episodes.add(episode)
        }
        fun addEpisodes(response: Response) {
            val document = response.asJsoup()
            val url = response.request.url.toString()
            if (document.select(seasonsNextPageSelector()).isNullOrEmpty()) {
                addEpisodeNew("$url/watch/", "مشاهدة")
            } else {
                document.select(seasonsNextPageSelector()).reversed().forEach { season ->
                    val seasonNum = season.select("h3").text()
                    (
                        if (seasonNum == document.selectFirst("div#mpbreadcrumbs a span:contains(الموسم)")!!.text()) {
                            document
                        } else {
                            client.newCall(GET(season.selectFirst("a")!!.attr("href"))).execute().asJsoup()
                        }
                        )
                        .select("section.allepcont a").forEach { episode ->
                            addEpisodeNew(
                                episode.attr("href") + "watch/",
                                seasonNum + " : الحلقة " + episode.select("div.epnum").text().filter { it.isDigit() },
                            )
                        }
                }
            }
        }
        addEpisodes(response)
        return episodes
    }

    override fun episodeListSelector() = "link[rel=canonical]"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ video links ============================
    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl/${episode.url}", headers)
    }

    override fun videoListSelector() = "div.watch--servers--list ul li.server--item"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector())
            .parallelCatchingFlatMapBlocking(::videosFromElement)
    }

    private fun videosFromElement(element: Element): List<Video> {
        val url = element.attr("data-link")
        val txt = element.text()
        return when {
            "iframe" in url -> {
                val newHeaders = headers.newBuilder().apply {
                    add("Referer", "$baseUrl/")
                    add("X-Inertia", "true")
                    add("X-Inertia-Partial-Component", "files/mirror/video")
                    add("X-Inertia-Partial-Data", "streams")
                    add("X-Inertia-Version", "933f5361ce18c71b82fa342f88de9634")
                    add("X-Requested-With", "XMLHttpRequest")
                }.build()
                val encodedData = client.newCall(GET(url, newHeaders)).execute().body.string()
                // val jsonData = json.decodeFromString<IFrameResponse>(encodedData)
                Video(encodedData, encodedData, encodedData).let(::listOf)
                // jsonData.props.streams.data.parallelCatchingFlatMapBlocking { data ->
                //    data.mirrors.parallelCatchingFlatMapBlocking { mirror ->
                //        extractVideos(mirror.link, mirror.driver)
                //    }
                // }
            }
            else -> {
                extractVideos(url, txt)
            }
        }
    }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    private fun extractVideos(url: String, txt: String): List<Video> {
        return when {
            "ok.ru" in url -> {
                okruExtractor.videosFromUrl(url)
            }
            "Vidbom" in txt || "Vidshare" in txt || "Govid" in txt -> {
                vidBomExtractor.videosFromUrl(url, headers, txt)
            }
            "dood" in txt -> {
                doodExtractor.videoFromUrl(url, "Dood mirror")?.let(::listOf)
            }
            "mp4" in txt -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }
            "Upstream" in txt || "streamwish" in txt || "vidhide" in txt -> {
                streamWishExtractor.videosFromUrl(url, txt)
            }
            "mixdrop" in txt -> {
                mixDropExtractor.videosFromUrl(url)
            }
            else -> null
        } ?: emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ search ============================

    override fun searchAnimeSelector(): String = "div.Block--Item"

    override fun searchAnimeNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/?s=$query&page=$page"
        } else {
            val url = baseUrl
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/$catQ/?page=$page/"
                            return GET(catUrl, headers)
                        }
                    }
                    else -> {}
                }
            }
            return GET(url, headers)
        }
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("h3").text(), true).trim()
        anime.thumbnail_url = element.select("img").attr(if (element.ownerDocument()!!.location().contains("?s="))"src" else "data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href") + "watch/")
        return anime
    }

    // ============================ details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.genre = document.select("div.catssection li a").joinToString(", ") { it.text() }
        anime.title = titleEdit(document.select("h1.post-title").text()).trim()
        anime.author = document.select("ul.RightTaxContent li:contains(دولة) a").text()
        anime.description = document.select("div.story").text().trim()
        anime.status = SAnime.COMPLETED
        anime.thumbnail_url = document.select("div.left div.image img").attr("src")
        return anime
    }

    // ============================ latest ============================

    override fun latestUpdatesSelector(): String = "div.Block--Item"

    override fun latestUpdatesNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("a").attr("title"), true).trim()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href") + "watch/")
        return anime
    }

    // ============================ filters ============================

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)
    private data class CatUnit(val name: String, val query: String)
    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("كل الافلام", "category/movies-33/"),
        CatUnit("افلام اجنبى", "category/movies-33/افلام-اجنبي/"),
        CatUnit("افلام انمى", "category/anime-6/افلام-انمي/"),
        CatUnit("افلام تركيه", "category/movies-33/افلام-تركي/"),
        CatUnit("افلام اسيويه", "category/movies-33/افلام-اسيوي/"),
        CatUnit("افلام هنديه", "category/movies-33/افلام-هندى/"),
        CatUnit("كل المسسلسلات", "category/series-9/"),
        CatUnit("مسلسلات اجنبى", "category/series-9/مسلسلات-اجنبي/"),
        CatUnit("مسلسلات انمى", "category/anime-6/انمي-مترجم/"),
        CatUnit("مسلسلات تركى", "category/series-9/مسلسلات-تركي/"),
        CatUnit("مسلسلات اسيوى", "category/series-9/مسلسلات-أسيوي/"),
        CatUnit("مسلسلات هندى", "category/series-9/مسلسلات-هندي/"),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("720p", "480p", "Low", "Normal", "HD", "UHD", "DoodStream", "Uqload")
            entryValues = arrayOf("720", "480", "Low", "Normal", "HD", "UHD", "Dood", "Uqload")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
