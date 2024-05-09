package eu.kanade.tachiyomi.animeextension.ar.cimalek

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
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
    override fun episodeFromElement(element: Element): SEpisode {
        TODO("Not yet implemented")
    }

    override fun episodeListSelector(): String {
        TODO("Not yet implemented")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        TODO("Not yet implemented")
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
            Pair("افلام كرتون", "cartoon-movies"),
            Pair("افلام هندي", "indian-movies"),
            Pair("افلام اسيوي", "asian-aflam"),
            Pair("افلام انمي", "anime-movies"),
        ),
    )
    private class CategoryFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام", "movies-cats"),
            Pair("مسلسلات", "series_genres"),
            Pair("انمى", "anime-cats"),
        ),
    )
    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "Action", "Adventure", "Animation", "Western", "Sport", "Short", "Documentary", "Fantasy", "Sci-fi", "Romance", "Comedy", "Family", "Drama", "Thriller", "Crime", "Horror", "Biography",
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
