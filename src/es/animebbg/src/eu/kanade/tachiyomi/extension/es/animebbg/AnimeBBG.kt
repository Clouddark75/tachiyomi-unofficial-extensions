package eu.kanade.tachiyomi.extension.es.animebbg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeBBG : ParsedHttpSource() {

    override val name = "AnimeBBG"
    override val baseUrl = "https://animebbg.net"
    override val lang = "es"
    override val supportsLatest = true

    // Popular manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun popularMangaSelector() = "a[data-tp-primary='on']"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.text().trim()
            // We'll get thumbnail from manga details page since it's not in the list
        }
    }

    override fun popularMangaNextPageSelector() = "a.pageNavSimple-el--next"

    // Latest manga - same structure as popular
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/comics/".toHttpUrl().newBuilder()
        if (query.isNotEmpty()) {
            url.addQueryParameter("search", query)
        }
        url.addQueryParameter("page", page.toString())
        return GET(url.build().toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            // Title
            title = document.selectFirst("h1.p-title-value")?.text()?.trim() ?: ""
            
            // Cover image
            thumbnail_url = document.selectFirst("img[alt='Resource banner']")?.attr("src")
            
            // Alternative titles
            val altTitles = document.selectFirst("dd")?.html()?.split("<br>")
                ?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (!altTitles.isNullOrEmpty()) {
                description = "Títulos alternativos: ${altTitles.joinToString(", ")}\n\n"
            }
            
            // Genres
            val genres = document.select("dd .tagItem")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            genre = genres.joinToString(", ")
            
            // Description
            val desc = document.selectFirst(".bbWrapper")?.text()?.trim()
            if (!desc.isNullOrEmpty()) {
                description = (description ?: "") + desc
            }
            
            // Status - you might need to add logic to determine status
            status = SManga.UNKNOWN
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}/capitulos", headers)
    }

    override fun chapterListSelector() = ".structItem-title a"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = element.text().trim()
            // You might want to add date parsing if available
            date_upload = 0L
        }
    }

    // Handle chapter list pagination
    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        
        // Parse current page chapters
        chapters.addAll(document.select(chapterListSelector()).map { chapterFromElement(it) })
        
        // Check for next pages
        var nextPageUrl = document.selectFirst("a.pageNavSimple-el--next")?.attr("href")
        
        while (!nextPageUrl.isNullOrEmpty()) {
            val nextResponse = client.newCall(GET("$baseUrl$nextPageUrl", headers)).execute()
            val nextDocument = nextResponse.asJsoup()
            
            chapters.addAll(nextDocument.select(chapterListSelector()).map { chapterFromElement(it) })
            
            nextPageUrl = nextDocument.selectFirst("a.pageNavSimple-el--next")?.attr("href")
            nextResponse.close()
        }
        
        return chapters.reversed() // Reverse to show latest chapters first
    }

    // Page list
    override fun pageListParse(document: Document): List<Page> {
        return document.select(".media-container a").mapIndexed { index, element ->
            Page(index, "", element.attr("href"))
        }
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.attr("src") ?: ""
    }

    // Additional methods that might be needed
    override fun getFilterList() = FilterList()

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    }
}
