package eu.kanade.tachiyomi.animeextension.ar.arablionz

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
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
    override fun searchAnimeFromElement(element: Element): SAnime {
        TODO("Not yet implemented")
    }

    override fun searchAnimeNextPageSelector(): String? {
        TODO("Not yet implemented")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    override fun searchAnimeSelector(): String {
        TODO("Not yet implemented")
    }

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

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
