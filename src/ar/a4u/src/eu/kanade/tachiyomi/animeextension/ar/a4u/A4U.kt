package eu.kanade.tachiyomi.animeextension.ar.a4u

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
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
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class A4U: ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "A4U"

    override val baseUrl = "https://anime4up.cam"

    override val lang = "ar"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }


    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.anime-list-content div.anime-card-poster div.hover"

    override fun popularAnimeRequest(page: Int): Request =  GET("$baseUrl/anime-list-3/page/$page/", headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt")
        }
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:contains(»)"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.ehover6 > div.episodes-card-title > h3 > a, ul.all-episodes-list li > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = name.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = document // Shortcut

        thumbnail_url = doc.selectFirst("img.thumbnail")!!.attr("src")
        title = doc.selectFirst("h1.anime-details-title")!!.text()
        // Genres + useful info
        genre = doc.select("ul.anime-genres > li > a, div.anime-info > a").eachText().joinToString()

        description = buildString {
            // Additional info
            doc.select("div.anime-info").eachText().forEach {
                append("$it\n")
            }
            // Description
            doc.selectFirst("p.anime-story")?.text()?.also {
                append("\n$it")
            }
        }

        doc.selectFirst("div.anime-info:contains(حالة الأنمي)")?.text()?.also {
            status = when {
                it.contains("يعرض الان", true) -> SAnime.ONGOING
                it.contains("مكتمل", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================ Video Links =============================
    @Serializable
    data class Qualities(
        val fhd: Map<String, String> = emptyMap(),
        val hd: Map<String, String> = emptyMap(),
        val sd: Map<String, String> = emptyMap(),
    )

    override fun videoListParse(response: Response): List<Video> {
        val base64 = response.asJsoup().selectFirst("input[name=wl]")
            ?.attr("value")
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
            ?: return emptyList()

        val parsedData = json.decodeFromString<Qualities>(base64)
        val streamLinks = with(parsedData) { fhd + hd + sd }

        return streamLinks.values.distinct().parallelCatchingFlatMapBlocking(::extractVideos)
    }

    private fun extractVideos(url: String) = Video(url, url, url).let(::listOf)

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
            val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
            val url = baseUrl.toHttpUrl().newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (genreFilter.state != 0) {
                url.addPathSegment(genreFilter.toUriPart().lowercase())
            } else {
                throw Exception("من فضلك اختر قسم او تصنيف")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يمكنك تصفح اقسام الموقع اذا كان البحث فارغ"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("التصنيفات تعمل اذا كان 'اقسام الموقع' على 'اختر' فقط"),
        GenreFilter(),
    )

    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", "none"),
        ),
    )

    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "اختر", "", "",
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
    override fun latestUpdatesSelector(): String = "div.anime-list-content div.anime-card-container"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episode/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt") + " (${element.select(".episodes-card-title h3").text()})"
        }
        setUrlWithoutDomain(element.select(".anime-card-details h3 a").attr("href"))
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

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

}
