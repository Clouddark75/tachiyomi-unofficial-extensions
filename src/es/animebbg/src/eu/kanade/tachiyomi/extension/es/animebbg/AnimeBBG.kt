package eu.kanade.tachiyomi.extension.es.animebbg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeBBG : ParsedHttpSource() {

    override val name = "AnimeBBG"
    override val baseUrl = "https://animebbg.net"
    override val lang = "es"
    override val supportsLatest = true

    override fun popularMangaSelector(): String = "a[data-tp-primary='on']"
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun popularMangaNextPageSelector(): String = "a.pageNavSimple-el--next"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    override fun chapterListSelector(): String = ".structItem--resourceAlbum .structItem-title a"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("comics")
            addPathSegment("")
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) {
                addQueryParameter("search", query)
            }
        }
        return GET(url.build(), headers)
    }

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.text().trim()
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.p-title-value")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("img[alt='Resource banner']")?.attr("src")

        val altTitles = document.selectFirst("dd")?.html()?.split("<br>")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
        var desc = ""
        if (!altTitles.isNullOrEmpty()) {
            desc = "Títulos alternativos: ${altTitles.joinToString(", ")}\n\n"
        }

        val mainDesc = document.selectFirst(".bbWrapper")?.text()?.trim()
        if (!mainDesc.isNullOrEmpty()) {
            desc += mainDesc
        }
        description = desc

        genre = document.select("dd .tagItem").joinToString { it.text().trim() }
        status = SManga.UNKNOWN
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}/capitulos", headers)
    }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.attr("href")
        setUrlWithoutDomain(link)
        name = element.text().trim()
        date_upload = 0L
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(".media-container a").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("href"))
        }
    }

    override fun imageUrlParse(document: Document): String = ""
}
