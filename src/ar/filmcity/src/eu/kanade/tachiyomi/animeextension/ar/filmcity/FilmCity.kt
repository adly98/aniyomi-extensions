package eu.kanade.tachiyomi.animeextension.ar.filmcity

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FilmCity : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Film City"

    override val baseUrl = "https://m.filmcity12.com/i.php"

    override val lang = "ar"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "body div a"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(popularAnimeSelector()).map(::popularAnimeFromElement)
        return AnimesPage(animeList, false)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val webUrl = element.attr("href")
        val poster = element.select("img")
        return SAnime.create().apply {
            setUrlWithoutDomain(webUrl)
            title = poster.attr("alt").let {
                val cat = element.select(".Cat").text()
                when {
                    cat == "فلم" && url.contains("/d/") -> {
                        "$it (سلسلة افلام)"
                    }
                    cat == "فلم" && url.contains("/i/") -> {
                        "$it (فيلم)"
                    }
                    else -> "$it (مسلسل)"
                }
            }
            thumbnail_url = poster.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val webUrl = response.request.url.toString()
        return when {
            webUrl.contains("/i/") -> {
                SEpisode.create().apply {
                    name = "مشاهدة"
                    url = webUrl
                }.let(::listOf)
            }
            else -> {
                val doc = response.asJsoup()
                doc.select(episodeListSelector()).map { epFromElement(it, webUrl) }
            }
        }
    }

    private fun epFromElement(element: Element, webUrl: String): SEpisode {
        val title = element.text().replace(".mp4", "")
        val season = title.substringBefore("E", "1")
        val episode = title.substringAfter("E")
        return SEpisode.create().apply {
            name = episode + "الحلقة "
            url = "$webUrl/$title"
            episode_number = ("$season.$episode").toFloatOrNull() ?: 1F
        }
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListSelector(): String = ".listing__items .listing-item__info"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoListSelector(): String = ""

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val boundary = "----WebKitFormBoundaryfX9BPLRbUMYpdG3L"
        val mediaType = "multipart/form-data; boundary=$boundary".toMediaTypeOrNull()

        val requestBody = MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addFormDataPart("search_box", "8")
            .addFormDataPart("submited", "إبحث")
            .build()
        val nHeaders = headers.newBuilder()
            .add("Content-Type", mediaType.toString())
            .build()
        return POST(baseUrl, body = requestBody, headers = nHeaders)
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "fill"

    // =============================== Latest ===============================
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

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
