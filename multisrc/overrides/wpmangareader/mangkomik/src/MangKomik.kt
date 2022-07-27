package eu.kanade.tachiyomi.extension.id.mangkomik

import eu.kanade.tachiyomi.multisrc.wpmangareader.WPMangaReader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import org.jsoup.nodes.Document

class MangKomik : WPMangaReader("MangKomik", "https://mangkomik.com", "id") {
    override val hasProjectPage = true

    override fun pageListParse(document: Document): List<Page> {
        // Get external JS for image urls
        val scriptEl = document.selectFirst("script[data-minify]")
        val scriptUrl = scriptEl?.attr("src")
        if (scriptUrl.isNullOrEmpty()) {
            return super.pageListParse(document)
        }

        val scriptResponse = client.newCall(
            GET(scriptUrl, headers)
        ).execute()

        // Inject external JS
        scriptEl.text(scriptResponse.body!!.string())
        return super.pageListParse(document)
    }
}