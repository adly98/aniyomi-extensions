package eu.kanade.tachiyomi.animeextension.ar.shahid4u

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors.UQLoadExtractor
import eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors.VidBomExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Shahid4U : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "شاهد فور يو"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.glide-slides div.media-block"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("h3").text()).trim()
        anime.thumbnail_url = element.select("a.image img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a.fullClick").attr("href") + "watch/")
        return anime
    }

    private fun titleEdit(title: String, details: Boolean = false): String {
        return if (Regex("فيلم (.*?) مترجم").containsMatchIn(title)) {
            Regex("فيلم (.*?) مترجم").find(title)!!.groupValues[1] + " (فيلم)" // افلام اجنبيه مترجمه
        } else if (Regex("فيلم (.*?) مدبلج").containsMatchIn(title)) {
            Regex("فيلم (.*?) مدبلج").find(title)!!.groupValues[1] + " (مدبلج)(فيلم)" // افلام اجنبيه مدبلجه
        } else if (Regex("فيلم ([^a-zA-Z]+) ([0-9]+)").containsMatchIn(title)) {
            // افلام عربى
            Regex("فيلم ([^a-zA-Z]+) ([0-9]+)").find(title)!!.groupValues[1] + " (فيلم)"
        } else if (title.contains("مسلسل")) {
            if (title.contains("الموسم") and details) {
                val newTitle = Regex("مسلسل (.*?) الموسم (.*?) الحلقة ([0-9]+)").find(title)
                return "${newTitle!!.groupValues[1]} (م.${newTitle.groupValues[2]})(${newTitle.groupValues[3]}ح)"
            } else if (title.contains("الحلقة") and details) {
                val newTitle = Regex("مسلسل (.*?) الحلقة ([0-9]+)").find(title)
                return "${newTitle!!.groupValues[1]} (${newTitle.groupValues[2]}ح)"
            } else {
                Regex(if (title.contains("الموسم")) "مسلسل (.*?) الموسم" else "مسلسل (.*?) الحلقة").find(title)!!.groupValues[1] + " (مسلسل)"
            }
        } else if (title.contains("انمي")) {
            return Regex(if (title.contains("الموسم"))"انمي (.*?) الموسم" else "انمي (.*?) الحلقة").find(title)!!.groupValues[1] + " (انمى)"
        } else if (title.contains("برنامج")) {
            Regex(if (title.contains("الموسم"))"برنامج (.*?) الموسم" else "برنامج (.*?) الحلقة").find(title)!!.groupValues[1] + " (برنامج)"
        } else {
            title
        }
    }

    override fun popularAnimeNextPageSelector(): String = ".noNext"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val episodes = document.select(episodeListSelector())
        return when {
            episodes.isNullOrEmpty() -> {
                SEpisode.create().apply {
                    setUrlWithoutDomain(url)
                    name = "مشاهدة"
                }.let(::listOf)
            }
            else -> {
                episodes.map {
                    SEpisode.create().apply {
                        setUrlWithoutDomain(it.attr("href"))
                        name = it.text()
                    }
                }.toList()
            }
        }
    }

    override fun episodeListSelector() = "ul.episodes-list li a"

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        fun getUrl(v_id: String, i: String): String {
            val refererHeaders = Headers.headersOf(
                "referer",
                response.request.url.toString(),
                "x-requested-with",
                "XMLHttpRequest",
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
                "user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36 Edg/105.0.1343.42",
            )
            val requestBody = FormBody.Builder().add("id", v_id).add("i", i).build()
            val iframe = client.newCall(
                POST(
                    "$baseUrl/wp-content/themes/Shahid4u-WP_HOME/Ajaxat/Single/Server.php",
                    refererHeaders,
                    requestBody,
                ),
            ).execute().asJsoup()
            return iframe.select("iframe").attr("src")
        }
        return document.select(videoListSelector()).parallelMap { server ->
            val streamURL = getUrl(server.attr("data-id"), server.attr("data-i"))
            runCatching {
                when{
                    VIDBOM_REGEX.containsMatchIn(streamURL) -> {
                        val finalUrl = VIDBOM_REGEX.find(streamURL)!!
                        VidBomExtractor(client).videosFromUrl("https://www.${finalUrl.groupValues[0]}.html", finalUrl.groupValues[1])
                    }
                    UQLOAD_REGEX.containsMatchIn(streamURL) -> {
                        val finalUrl = UQLOAD_REGEX.find(streamURL)!!
                        UQLoadExtractor(client).videosFromUrl("https://www.${finalUrl.groupValues[0]}.html", finalUrl.groupValues[1])
                    }
                    STREAMWISH_REGEX.containsMatchIn(streamURL) -> {
                        val headers = headers.newBuilder()
                            .set("Referer", streamURL)
                            .set("Accept-Encoding", "gzip, deflate, br")
                            .set("Accept-Language", "en-US,en;q=0.5")
                            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                            .build()
                        val host = STREAMWISH_REGEX.find(streamURL)!!.groupValues[1]
                        StreamWishExtractor(client).videosFromUrl(streamURL, host, headers)
                    }
                    "ok.ru" in streamURL -> {
                        OkruExtractor(client).videosFromUrl(streamURL)
                    }
                    else ->  emptyList()
                }
            }.getOrElse { emptyList() }
        }.flatten()
    }

    override fun videoListSelector() = "ul.servers-list li.server--item"

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        var link = element.select("a.fullClick").attr("href")
        anime.title = titleEdit(element.select("h3").text(), details = true).trim()
        if (link.contains("assemblies")) {
            anime.thumbnail_url = element.select("a.image img").attr("data-src")
        } else {
            anime.thumbnail_url = element.select("a.image img.imgInit").attr("data-image")
        }
        if (!link.contains("assemblies")) link += "watch/"
        anime.setUrlWithoutDomain(link)
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.paginate ul.page-numbers li.active + li a"

    override fun searchAnimeSelector(): String = "div.MediaGrid div.media-block"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            val url = "$baseUrl/home2/page/$page"
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/$catQ/page/$page/"
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

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // div.CoverSingle div.CoverSingleContent
        anime.genre = document.select("div.SingleDetails li:contains(النوع) a").joinToString(", ") { it.text() }
        anime.title = titleEdit(document.select("meta[property=og:title]").attr("content")).trim()
        anime.author = document.select("div.SingleDetails li:contains(دولة) a").text()
        anime.description = document.select("div.ServersEmbeds section.story").text().replace(document.select("meta[property=og:title]").attr("content"), "").replace(":", "").trim()
        anime.status = SAnime.COMPLETED
        if (anime.title.contains("سلسلة")) anime.thumbnail_url = document.selectFirst("img.imgInit")!!.attr("data-image")
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "div.paginate ul.page-numbers li.active + li a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("a h3").text()).trim()
        anime.thumbnail_url = element.select("a.image").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a.fullClick").attr("href").replace(Regex("episode|film"), "watch"))
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(if(page == 1) baseUrl else "$baseUrl/?page=$page")

    override fun latestUpdatesSelector(): String = "div.MediaGrid div.media-block"

    // Filters

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
        CatUnit("افلام اجنبى", "category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-3/"),
        CatUnit("افلام انمى", "category/افلام-انمي/"),
        CatUnit("افلام تركيه", "category/افلام-تركية/"),
        CatUnit("افلام اسيويه", "category/افلام-اسيوية/"),
        CatUnit("افلام هنديه", "category/افلام-هندي-1/"),
        CatUnit("سلاسل افلام", "assemblies/"),
        CatUnit("مسلسلات اجنبى", "category/مسلسلات-اجنبي-1/"),
        CatUnit("مسلسلات انمى", "category/مسلسلات-انمي-4/"),
        CatUnit("مسلسلات تركى", "category/مسلسلات-تركي-3/"),
        CatUnit("مسلسلات اسيوى", "category/مسلسلات-اسيوي/"),
        CatUnit("مسلسلات هندى", "category/مسلسلات-هندية/"),
    )

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = PREF_BASE_URL_TITLE
            summary = getPrefBaseUrl()
            this.setDefaultValue(PREF_BASE_URL_DEFAULT)
            dialogTitle = PREF_BASE_URL_DIALOG_TITLE
            dialogMessage = PREF_BASE_URL_DIALOG_MESSAGE

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(PREF_BASE_URL_KEY, newValue as String).commit()
                    Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES.map { it.replace("p","") }.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(baseUrlPref)
        screen.addPreference(videoQualityPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT)!!

    // ============================= Utilities ===================================
    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")

        private const val PREF_BASE_URL_DEFAULT = "https://shhahed4u.com"
        private const val PREF_BASE_URL_KEY = "default_domain"
        private const val PREF_BASE_URL_TITLE = "Enter default domain"
        private const val PREF_BASE_URL_DIALOG_TITLE = "Default domain"
        private const val PREF_BASE_URL_DIALOG_MESSAGE = "You can change the site domain from here"

        private val VIDBOM_REGEX = Regex("(v[aie]d[bp][aoe]?m|myvii?d|govad|segavid|v[aei]{1,2}dshar[er]?)\\.(?:com|net|org|xyz)(?::\\d+)?/(?:embed[/-])?([A-Za-z0-9]+)")
        private val UQLOAD_REGEX = Regex("(uqload|vudeo)\\.[ic]om?/(?:embed-)?([0-9a-zA-Z]+)")
        private val STREAMWISH_REGEX = Regex("(embedwish|filelions)\\.(?:com|to|sbs)/(?:e/|v/|f/)?([0-9a-zA-Z]+)")

    }
}
