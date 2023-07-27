package eu.kanade.tachiyomi.animeextension.ar.asktv

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AskTv: ParsedAnimeHttpSource() {

    override val name = "قصة عشق"

    override val baseUrl = "https://bit.ly/3sktvtr"

    override val lang = "ar"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply{
            thumbnail_url = element.select(".imgSer").attr("style")
                .substringAfter("url(").substringBefore(")")
            title = element.select(".title").text()
            setUrlWithoutDomain(element.select("a").attr("href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.navigation div.pagination a:contains(›)"

    override fun popularAnimeRequest(page: Int): Request = GET("/all-series/page/$page/")

    override fun popularAnimeSelector(): String = "article.postEp"

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
        return SAnime.create().apply{
            thumbnail_url = element.select(".imgBg").attr("style")
                .substringAfter("url(").substringBefore(")")
            title = element.select(".title").text()
            val url = element.select("a").attr("href")
                .substringAfter("url=").replace("%3D","")
            setUrlWithoutDomain(url.decodeBase64())
        }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("/episodes/page/$page/")

    override fun latestUpdatesSelector(): String = "article.postEp"

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
