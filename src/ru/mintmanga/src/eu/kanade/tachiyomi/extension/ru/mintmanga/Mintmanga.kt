package eu.kanade.tachiyomi.extension.ru.mintmanga

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

class Mintmanga : ConfigurableSource, ParsedHttpSource() {

    override val id: Long = 6
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    override val name = "Mintmanga"

    override val baseUrl = "https://mintmanga.live"

    override val lang = "ru"

    override val supportsLatest = true

    private val rateLimitInterceptor = RateLimitInterceptor(2)

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor)
        .addNetworkInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (originalRequest.url.toString().contains(baseUrl) and (originalRequest.url.toString().contains("internal/redirect") or (response.code == 301)))
                throw Exception("Манга переехала на другой адрес/ссылку!")
            response
        }
        .build()

    private var uagent: String = preferences.getString(UAGENT_TITLE, UAGENT_DEFAULT)!!
    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", uagent)
        add("Referer", baseUrl)
    }

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/list?sortType=rate&offset=${70 * (page - 1)}", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/list?sortType=updated&offset=${70 * (page - 1)}", headers)

    override fun popularMangaSelector() = "div.tile"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("img.lazy").first()?.attr("data-original")
        element.select("h3 > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.attr("title")
        }
        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.nextLink"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/advanced".toHttpUrlOrNull()!!.newBuilder()
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(genre.id, arrayOf("=", "=in", "=ex")[genre.state])
                    }
                }
                is Category -> filter.state.forEach { category ->
                    if (category.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(category.id, arrayOf("=", "=in", "=ex")[category.state])
                    }
                }
                is AgeList -> filter.state.forEach { age ->
                    if (age.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(age.id, arrayOf("=", "=in", "=ex")[age.state])
                    }
                }
                is More -> filter.state.forEach { more ->
                    if (more.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(more.id, arrayOf("=", "=in", "=ex")[more.state])
                    }
                }
                is FilList -> filter.state.forEach { fils ->
                    if (fils.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(fils.id, arrayOf("=", "=in", "=ex")[fils.state])
                    }
                }
                is OrderBy -> {
                    if (filter.state > 0) {
                        val ord = arrayOf("not", "year", "rate", "popularity", "votes", "created", "updated")[filter.state]
                        val ordUrl = "$baseUrl/list?sortType=$ord&offset=${70 * (page - 1)}".toHttpUrlOrNull()!!.newBuilder()
                        return GET(ordUrl.toString(), headers)
                    }
                }
            }
        }
        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }
        return GET(url.toString().replace("=%3D", "="), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    // max 200 results (exception OrderBy)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select(".expandable").first()
        val rawCategory = infoElement.select("span.elem_category").text()
        val category = if (rawCategory.isNotEmpty()) {
            rawCategory.toLowerCase()
        } else {
            "манга"
        }
        val ratingValue = infoElement.select(".col-sm-7 .rating-block").attr("data-score").toFloat() * 2
        val ratingValueOver = infoElement.select(".info-icon").attr("data-content").substringBeforeLast("/5</b><br/>").substringAfterLast(": <b>").replace(",", ".").toFloat() * 2
        val ratingVotes = infoElement.select(".col-sm-7 .user-rating meta[itemprop=\"ratingCount\"]").attr("content")
        val ratingStar = when {
            ratingValue > 9.5 -> "★★★★★"
            ratingValue > 8.5 -> "★★★★✬"
            ratingValue > 7.5 -> "★★★★☆"
            ratingValue > 6.5 -> "★★★✬☆"
            ratingValue > 5.5 -> "★★★☆☆"
            ratingValue > 4.5 -> "★★✬☆☆"
            ratingValue > 3.5 -> "★★☆☆☆"
            ratingValue > 2.5 -> "★✬☆☆☆"
            ratingValue > 1.5 -> "★☆☆☆☆"
            ratingValue > 0.5 -> "✬☆☆☆☆"
            else -> "☆☆☆☆☆"
        }
        val rawAgeValue = infoElement.select(".elem_limitation .element-link").first()?.text()
        val rawAgeStop = when (rawAgeValue) {
            "NC-17" -> "18+"
            "R18+" -> "18+"
            else -> "16+"
        }
        val manga = SManga.create()
        var authorElement = infoElement.select("span.elem_author").first()?.text()
        if (authorElement == null) {
            authorElement = infoElement.select("span.elem_screenwriter").first()?.text()
        }
        manga.title = document.select("h1.names .name").text()
        manga.author = authorElement
        manga.artist = infoElement.select("span.elem_illustrator").first()?.text()
        manga.genre = infoElement.select("span.elem_genre").text().split(",").plusElement(category).plusElement(rawAgeStop).joinToString { it.trim() }
        var altName = ""
        if (infoElement.select(".another-names").isNotEmpty()) {
            altName = "Альтернативные названия:\n" + infoElement.select(".another-names").text() + "\n\n"
        }
        manga.description = ratingStar + " " + ratingValue + "[ⓘ" + ratingValueOver + "]" + " (голосов: " + ratingVotes + ")\n" + altName + document.select("div#tab-description  .manga-description").text()
        manga.status = parseStatus(infoElement.html())
        manga.thumbnail_url = infoElement.select("img").attr("data-full")
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.contains("Запрещена публикация произведения по копирайту") || element.contains("ЗАПРЕЩЕНА К ПУБЛИКАЦИИ НА ТЕРРИТОРИИ РФ!") -> SManga.LICENSED
        element.contains("<b>Перевод:</b> продолжается") -> SManga.ONGOING
        element.contains("<b>Сингл</b>") || element.contains("<b>Перевод:</b> завер") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return if (manga.status != SManga.LICENSED) {
            client.newCall(chapterListRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    chapterListParse(response, manga)
                }
        } else {
            Observable.error(java.lang.Exception("Licensed - No chapters to show"))
        }
    }

    private fun chapterListParse(response: Response, manga: SManga): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).map { chapterFromElement(it, manga) }
    }

    override fun chapterListSelector() = "div.chapters-link > table > tbody > tr:has(td > a)"

    private fun chapterFromElement(element: Element, manga: SManga): SChapter {
        val urlElement = element.select("a").first()
        val chapterInf = element.select("td.item-title").first()
        val urlText = urlElement.text()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href") + "?mtr=true") // mtr is 18+ skip

        var translators = ""
        val translatorElement = urlElement.attr("title")
        if (!translatorElement.isNullOrBlank()) {
            translators = translatorElement
                .replace("(Переводчик),", "&")
                .removeSuffix(" (Переводчик)")
        }
        chapter.scanlator = translators

        chapter.name = urlText.removeSuffix(" новое").trim()
        if (manga.title.length > 25) {
            for (word in manga.title.split(' ')) {
                chapter.name = chapter.name.removePrefix(word).trim()
            }
        }
        val dots = chapter.name.indexOf("…")
        val numbers = chapter.name.findAnyOf(IntRange(0, 9).map { it.toString() })?.first ?: 0

        if (dots in 0 until numbers) {
            chapter.name = chapter.name.substringAfter("…").trim()
        }

        chapter.chapter_number = chapterInf.attr("data-num").toFloat() / 10

        chapter.date_upload = element.select("td.d-none").last()?.text()?.let {
            try {
                SimpleDateFormat("dd.MM.yy", Locale.US).parse(it)?.time ?: 0L
            } catch (e: ParseException) {
                SimpleDateFormat("dd/MM/yy", Locale.US).parse(it)?.time ?: 0L
            }
        } ?: 0
        return chapter
    }

    override fun chapterFromElement(element: Element): SChapter {
        throw Exception("Not used")
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val extra = Regex("""\s*([0-9]+\sЭкстра)\s*""")
        val single = Regex("""\s*Сингл\s*""")
        when {
            extra.containsMatchIn(chapter.name) -> {
                if (chapter.name.substringAfter("Экстра").trim().isEmpty())
                    chapter.name = chapter.name.replaceFirst(" ", " - " + DecimalFormat("#,###.##").format(chapter.chapter_number).replace(",", ".") + " ")
            }

            single.containsMatchIn(chapter.name) -> {
                if (chapter.name.substringAfter("Сингл").trim().isEmpty())
                    chapter.name = DecimalFormat("#,###.##").format(chapter.chapter_number).replace(",", ".") + " " + chapter.name
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body!!.string()
        val beginIndex = html.indexOf("rm_h.initReader( [")
        val endIndex = html.indexOf(");", beginIndex)
        val trimmedHtml = html.substring(beginIndex, endIndex)

        val p = Pattern.compile("'.*?','.*?',\".*?\"")
        val m = p.matcher(trimmedHtml)

        val pages = mutableListOf<Page>()

        var i = 0
        while (m.find()) {
            val urlParts = m.group().replace("[\"\']+".toRegex(), "").split(',')
            val url = if (urlParts[1].isEmpty() && urlParts[2].startsWith("/static/")) {
                baseUrl + urlParts[2]
            } else {
                if (urlParts[1].endsWith("/manga/")) {
                    urlParts[0] + urlParts[2]
                } else {
                    urlParts[1] + urlParts[0] + urlParts[2]
                }
            }
            pages.add(Page(i++, "", url))
        }
        return pages
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val imgHeader = Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
            add("Referer", baseUrl)
        }.build()
        return GET(page.imageUrl!!, imgHeader)
    }
    private fun searchMangaByIdRequest(id: String): Request {
        return GET("$baseUrl/$id", headers)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/$realQuery"
                    MangasPage(listOf(details), false)
                }
        } else {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    private class OrderBy : Filter.Select<String>(
        "Сортировка (только)",
        arrayOf("Без сортировки", "По году", "По популярности", "Популярно сейчас", "По рейтингу", "Новинки", "По дате обновления")
    )

    private class Genre(name: String, val id: String) : Filter.TriState(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Жанры", genres)
    private class Category(categories: List<Genre>) : Filter.Group<Genre>("Категории", categories)
    private class AgeList(ages: List<Genre>) : Filter.Group<Genre>("Возрастная рекомендация", ages)
    private class More(moren: List<Genre>) : Filter.Group<Genre>("Прочее", moren)
    private class FilList(fils: List<Genre>) : Filter.Group<Genre>("Фильтры", fils)

    override fun getFilterList() = FilterList(
        OrderBy(),
        Category(getCategoryList()),
        GenreList(getGenreList()),
        AgeList(getAgeList()),
        More(getMore()),
        FilList(getFilList())
    )
    private fun getFilList() = listOf(
        Genre("Высокий рейтинг", "s_high_rate"),
        Genre("Сингл", "s_single"),
        Genre("Для взрослых", "s_mature"),
        Genre("Завершенная", "s_completed"),
        Genre("Переведено", "s_translated"),
        Genre("Длинная", "s_many_chapters"),
        Genre("Ожидает загрузки", "s_wait_upload"),
    )
    private fun getMore() = listOf(
        Genre("В цвете", "el_4614"),
        Genre("Веб", "el_1355"),
        Genre("Выпуск приостановлен", "el_5232"),
        Genre("Не Яой", "el_1874"),
        Genre("Сборник", "el_1348")
    )

    private fun getAgeList() = listOf(
        Genre("R(16+)", "el_3968"),
        Genre("NC-17(18+)", "el_3969"),
        Genre("R18+(18+)", "el_3990")
    )

    private fun getCategoryList() = listOf(
        Genre("Ёнкома", "el_2741"),
        Genre("Комикс западный", "el_1903"),
        Genre("Комикс русский", "el_2173"),
        Genre("Манхва", "el_1873"),
        Genre("Маньхуа", "el_1875"),
        Genre("Ранобэ", "el_5688"),
    )

    private fun getGenreList() = listOf(
        Genre("арт", "el_2220"),
        Genre("бара", "el_1353"),
        Genre("боевик", "el_1346"),
        Genre("боевые искусства", "el_1334"),
        Genre("вампиры", "el_1339"),
        Genre("гарем", "el_1333"),
        Genre("гендерная интрига", "el_1347"),
        Genre("героическое фэнтези", "el_1337"),
        Genre("детектив", "el_1343"),
        Genre("дзёсэй", "el_1349"),
        Genre("додзинси", "el_1332"),
        Genre("драма", "el_1310"),
        Genre("игра", "el_5229"),
        Genre("история", "el_1311"),
        Genre("киберпанк", "el_1351"),
        Genre("комедия", "el_1328"),
        Genre("меха", "el_1318"),
        Genre("научная фантастика", "el_1325"),
        Genre("омегаверс", "el_5676"),
        Genre("повседневность", "el_1327"),
        Genre("постапокалиптика", "el_1342"),
        Genre("приключения", "el_1322"),
        Genre("психология", "el_1335"),
        Genre("романтика", "el_1313"),
        Genre("самурайский боевик", "el_1316"),
        Genre("сверхъестественное", "el_1350"),
        Genre("сёдзё", "el_1314"),
        Genre("сёдзё-ай", "el_1320"),
        Genre("сёнэн", "el_1326"),
        Genre("сёнэн-ай", "el_1330"),
        Genre("спорт", "el_1321"),
        Genre("сэйнэн", "el_1329"),
        Genre("трагедия", "el_1344"),
        Genre("триллер", "el_1341"),
        Genre("ужасы", "el_1317"),
        Genre("фэнтези", "el_1323"),
        Genre("школа", "el_1319"),
        Genre("эротика", "el_1340"),
        Genre("этти", "el_1354"),
        Genre("юри", "el_1315"),
        Genre("яой", "el_1336")
    )
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(UAGENT_TITLE, UAGENT_DEFAULT, uagent))
    }

    private fun androidx.preference.PreferenceScreen.editTextPreference(title: String, default: String, value: String): androidx.preference.EditTextPreference {
        return androidx.preference.EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value
            this.setDefaultValue(default)
            dialogTitle = title
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Для смены User-Agent необходимо перезапустить приложение с полной остановкой.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }
    companion object {
        private const val UAGENT_TITLE = "User-Agent(для некоторых стран)"
        private const val UAGENT_DEFAULT = "arora"
        const val PREFIX_SLUG_SEARCH = "slug:"
    }
}