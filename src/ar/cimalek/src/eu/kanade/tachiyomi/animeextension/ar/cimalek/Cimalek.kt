package eu.kanade.tachiyomi.animeextension.ar.cimalek

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.cimalek.interceptor.GetSourcesInterceptor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Cimalek : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Cimalek"

    override val baseUrl = "https://m.cimaleek.to"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("div.data .title").text()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination div.pagination-num i#nextpagination"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/page/$page/")

    override fun popularAnimeSelector(): String = "div.film_list-wrap div.item"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val url = response.request.url.toString()
        if (url.contains("movies")) {
            val episode = SEpisode.create().apply {
                name = "مشاهدة"
                setUrlWithoutDomain("$url/watch/")
            }
            episodes.add(episode)
        } else {
            document.select(seasonListSelector()).parallelCatchingFlatMapBlocking { sElement ->
                val seasonNum = sElement.select("span.se-a").text()
                val seasonUrl = sElement.attr("href")
                val seasonPage = client.newCall(GET(seasonUrl)).execute().asJsoup()
                seasonPage.select(episodeListSelector()).map { eElement ->
                    val episodeNum = eElement.select("span.serie").text().substringAfter("(").substringBefore(")")
                    val episodeUrl = eElement.attr("href")
                    val finalNum = ("$seasonNum.$episodeNum").toFloat()
                    val episodeTitle = "الموسم $seasonNum الحلقة $episodeNum"
                    val episode = SEpisode.create().apply {
                        name = episodeTitle
                        episode_number = finalNum
                        setUrlWithoutDomain("$episodeUrl/watch/")
                    }
                    episodes.add(episode)
                }
            }
        }
        return episodes.sortedBy { it.episode_number }
    }

    override fun episodeListSelector(): String = "div.season-a ul.episodios li.episodesList a"

    private fun seasonListSelector(): String = "div.season-a ul.seas-list li.sealist a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.ani_detail-stage div.film-poster img").attr("src")
        anime.title = document.select("div.anisc-more-info div.item:contains(الاسم) span:nth-child(3)").text()
        anime.author = document.select("div.anisc-more-info div.item:contains(البلد) span:nth-child(3)").text()
        anime.genre = document.select("div.anisc-detail div.item-list a").joinToString(", ") { it.text() }
        anime.description = document.select("div.anisc-detail div.film-description div.text").text()
        anime.status = if (document.select("div.anisc-detail div.item-list").text().contains("افلام")) SAnime.COMPLETED else SAnime.UNKNOWN
        return anime
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListSelector(): String = "div#servers-content div.server-item div"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(dtAjax)")!!.data()
        val version = script.substringAfter("ver\":\"").substringBefore("\"")
        var index = 0
        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking {
            index++
            extractVideos(it, version, index)
        }
    }

    private fun extractVideos(element: Element, version: String, index: Int): List<Video> {
        fun generateRandomString(length: Int): String {
            val characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val result = StringBuilder(length)
            for (i in 0 until length) {
                val randomIndex = (Math.random() * characters.length).toInt()
                result.append(characters[randomIndex])
            }
            return result.toString()
        }
        val videoList = mutableListOf<Video>()
        val videoUrl = "$baseUrl/wp-json/lalaplayer/v2/".toHttpUrlOrNull()!!.newBuilder()
        videoUrl.addQueryParameter("p", element.attr("data-post"))
        videoUrl.addQueryParameter("t", element.attr("data-type"))
        videoUrl.addQueryParameter("n", element.attr("data-nume"))
        videoUrl.addQueryParameter("ver", version)
        videoUrl.addQueryParameter("rand", generateRandomString(16))
        val videoFrame = client.newCall(GET(videoUrl.toString())).execute().body.string()
        val embedUrl = videoFrame.substringAfter("embed_url\":\"").substringBefore("\"")
        val referer = headers.newBuilder().add("Referer", "$baseUrl/").build()
        val videoRegex = Regex("""m3u8""")
        val webViewInterceptor = client.newBuilder().addInterceptor(GetSourcesInterceptor(videoRegex)).build()
        val lol = webViewInterceptor.newCall(GET(embedUrl, referer)).execute()
        if (videoRegex.containsMatchIn(lol.request.url.toString())){
            lol.body.string().substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                val playUrl = it.substringAfter("\n").substringBefore("\n").replace("https", "http")
                videoList.add(Video(playUrl, "Server $index: $quality", playUrl, headers = referer))
            }
        }
        return videoList
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
        val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment("category")
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (categoryFilter.state != 0) {
                url.addPathSegment("genre")
                url.addPathSegment(genreFilter.toUriPart().lowercase())
            } else {
                throw Exception("من فضلك اختر قسم او نوع")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            if (categoryFilter.state != 0) {
                url.addQueryParameter("type", categoryFilter.toUriPart())
            }
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("هذا القسم يعمل لو كان البحث فارع"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("الفلتره تعمل فقط لو كان اقسام الموقع على 'اختر'"),
        CategoryFilter(),
        GenreFilter(),
    )
    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام اجنبي", "aflam-online"),
            Pair("افلام نتفليكس", "netflix-movies"),
            Pair("افلام هندي", "indian-movies"),
            Pair("افلام اسيوي", "asian-aflam"),
            Pair("افلام كرتون", "cartoon-movies"),
            Pair("افلام انمي", "anime-movies"),
            Pair("مسلسلات اجنبي", "english-series"),
            Pair("مسلسلات نتفليكس", "netflix-series"),
            Pair("مسلسلات اسيوي", "asian-series"),
            Pair("مسلسلات كرتون", "anime-series"),
            Pair("مسلسلات انمي", "netflix-anime"),
        ),
    )
    private class CategoryFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام", "movies"),
            Pair("مسلسلات", "series"),
        ),
    )
    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "Action", "Adventure", "Animation", "Western", "Documentary", "Fantasy", "Science-fiction", "Romance", "Comedy", "Family", "Drama", "Thriller", "Crime", "Horror",
        ).sortedArray(),
    )

    open class SingleFilter(displayName: String, private val vals: Array<String>) :
        AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state]
    }
    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent/page/$page/")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
        screen.addPreference(videoQualityPref)
    }

    // =============================== Utilities ===============================
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
}
