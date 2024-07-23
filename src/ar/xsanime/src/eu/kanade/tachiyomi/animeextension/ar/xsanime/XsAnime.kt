package eu.kanade.tachiyomi.animeextension.ar.xsanime

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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class XsAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "XS Anime"

    override val baseUrl = "https://ww.xsanime.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.block-post"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime_list/page/$page", headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("a").attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("data-img")
    }

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "#episodes a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        return if ("/movie/" in url) {
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "مشاهدة"
            }.let(::listOf)
        } else {
            document.select(episodeListSelector()).map(::episodeFromElement)
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.attr("title")
        episode_number = name.filter { it.isDigit() }.toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val srcVid = preferences.getString("preferred_quality", "الجودة العالية")!!
        val iframe = document.select("div.downloads ul div.listServ:contains($srcVid) div.serL a[href~=4shared]").attr("href").substringBeforeLast("/").replace("video", "web/embed/file")
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        element.attr("src")
        return Video(element.attr("src"), "Default: If you want to change the quality go to extension settings", element.attr("src"))
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            val url = "$baseUrl/anime_list/page/$page/?".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    // is SeasonFilter -> url.addQueryParameter("season", filter.toUriPart())
                    is GenreFilter -> url.addQueryParameter("genre", filter.toUriPart())
                    is StatusFilter -> url.addQueryParameter("status", filter.toUriPart())
                    // is LetterFilter -> url.addQueryParameter("letter", filter.toUriPart())
                    else -> {}
                }
            }
            GET(url.build().toString(), headers)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.posterWrapper div.poster").attr("style")
            .substringAfter("url(").substringBefore(")")
        anime.title = document.select("li.item-current.item-archive").text()
        anime.genre = document.select("div.singleInfoCon ul > li:contains(التصنيف) > a").joinToString(", ") { it.text() }
        anime.description = document.select("div.singleInfo div.story p").text()
        document.select("div.singleInfoCon ul:contains(عدد الحلقات)").text().filter { it.isDigit() }.toIntOrNull().also { episodesNum ->
            val episodesCount = document.select("#episodes a").size + 1
            when {
                episodesCount == episodesNum || episodesNum == null -> anime.status = SAnime.COMPLETED
                episodesCount < episodesNum -> anime.status = SAnime.ONGOING
                else -> anime.status = SAnime.UNKNOWN
            }
        }
        return anime
    }

    // =============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreFilters()),
        StatusFilter(getStatusFilters()),
        // SeasonFilter(getStatusFilters()),
        // LetterFilter(getLetterFilter()),
    )

    private class StatusFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("حالة الأنمي", vals)
    private class GenreFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("تصنيفات الانمى", vals)
    // private class SeasonFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("موسم الانمى", vals)
    // private class LetterFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("الحرف", vals)

    private fun getStatusFilters(): Array<Pair<String?, String>> = arrayOf(
        Pair("", "<اختر>"),
        Pair("مستمر", "مستمر"),
        Pair("منتهي", "منتهي"),
    )

    private fun getGenreFilters(): Array<Pair<String?, String>> = arrayOf(
        Pair("", "<اختر>"),
        Pair("أكشن", "أكشن"),
        Pair("تاريخي", "تاريخي"),
        Pair("حريم", "حريم"),
        Pair("خارق للطبيعة", "خارق للطبيعة"),
        Pair("خيال", "خيال"),
        Pair("دراما", "دراما"),
        Pair("رومانسي", "رومانسي"),
        Pair("رياضي", "رياضي"),
        Pair("سينين", "سينين"),
        Pair("شونين", "شونين"),
        Pair("شياطين", "شياطين"),
        Pair("غموض", "غموض"),
        Pair("قوى خارقة", "قوى خارقة"),
        Pair("كوميدي", "كوميدي"),
        Pair("لعبة", "لعبة"),
        Pair("مدرسي", "مدرسي"),
        Pair("مغامرات", "مغامرات"),
        Pair("موسيقي", "موسيقي"),
        Pair("نفسي", "نفسي"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String?, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.second }.toTypedArray()) {
        fun toUriPart() = vals[state].first
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val url = element.select("a").attr("href")
            .replace("episode", "anime").split("-").dropLast(2).joinToString("-")
        setUrlWithoutDomain(url)
        title = element.select("a").attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("data-img")
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episode/page/$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred Quality"
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
        screen.addPreference(qualityPref)
    }
}
