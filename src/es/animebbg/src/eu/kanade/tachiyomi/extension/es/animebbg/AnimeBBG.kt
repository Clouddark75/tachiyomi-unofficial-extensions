package eu.kanade.tachiyomi.extension.es.animebbg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeBBG : ParsedHttpSource() {

    override val name = "AnimeBBG"
    override val baseUrl = "https://animebbg.net"
    override val lang = "es"
    override val supportsLatest = true

    override fun popularMangaSelector(): String = "a[data-tp-primary='on']"
    override fun latestUpdatesSelector(): String = popularMangaSelector()

    // Selector específico para búsqueda que filtra solo comics
    override fun searchMangaSelector(): String = ".cse-result:has(a[href*='/comics/'])"

    override fun popularMangaNextPageSelector(): String = "a.pageNavSimple-el--next"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Selector para paginación en búsqueda
    override fun searchMangaNextPageSelector(): String = "a[aria-label='Go to the next page']"

    override fun chapterListSelector(): String = "div.md-chapter-row"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Paso 1: obtener el engineId dinámico
        val doc = client.newCall(GET("$baseUrl/search/?q=$query", headers)).execute().asJsoup()

        // Busca en scripts el engineId, ej: /search/198660/
        val script = doc.selectFirst("script:containsData(search/)")?.data()
        val engineId = Regex("/search/(\\d+)/").find(script ?: "")?.groupValues?.get(1)
            ?: throw Exception("No se pudo encontrar engine ID para búsqueda")

        // Paso 2: construir la url final de búsqueda
        val searchUrl = "$baseUrl/search/$engineId/?q=$query&o=date#gsc.tab=0&gsc.q=$query&gsc.page=$page"
        return GET(searchUrl, headers)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.text().trim()
        }

        // Cargar página del manga para obtener el thumbnail
        val response = client.newCall(GET(baseUrl + manga.url, headers)).execute()
        val detailsDoc = org.jsoup.Jsoup.parse(response.body!!.string())
        response.close()

        manga.thumbnail_url =
            detailsDoc.selectFirst("img[alt='Resource banner']")?.attr("src")

        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        // Buscar el enlace al comic dentro del resultado de búsqueda
        val linkElement = element.selectFirst("a[href*='/comics/']")
        if (linkElement != null) {
            manga.setUrlWithoutDomain(linkElement.attr("href"))

            // Extraer título del texto del enlace o del snippet
            val titleElement = element.selectFirst(".gsc-title-link")
                ?: element.selectFirst("a[href*='/comics/']")
            manga.title = titleElement?.text()?.trim()
                ?.replace(Regex("\\s*ES\\s*"), "")
                ?.replace(Regex("\\s*Manhua\\s*"), "")
                ?.replace(Regex("\\s*Manga\\s*"), "")
                ?.replace(Regex("\\s*Webtoon\\s*"), "")
                ?.replace(Regex("\\s*Comic\\s*"), "")
                ?.replace(Regex("\\s*Manhwa\\s*"), "")
                ?.replace(Regex("\\s*-\\s*AnimeBBG.*"), "")
                ?.trim() ?: ""

            // Intentar obtener thumbnail de la página de detalles si es necesario
            try {
                val response = client.newCall(GET(baseUrl + manga.url, headers)).execute()
                val detailsDoc = org.jsoup.Jsoup.parse(response.body!!.string())
                response.close()

                manga.thumbnail_url = detailsDoc.selectFirst("img[alt='Resource banner']")?.attr("src")
            } catch (e: Exception) {
                // Si falla, continuar sin thumbnail
                manga.thumbnail_url = ""
            }
        }

        return manga
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()

        val mangas = doc.select("div.structItem--comic").mapNotNull { element: Element ->
            val title = element.selectFirst(".structItem-title")?.text() ?: return@mapNotNull null

            // Filtramos resultados que sean capítulos
            if (title.contains("Capítulo", true) || title.contains("Capitulo", true)) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a.structItem-title")?.attr("href") ?: return@mapNotNull null)
                this.title = title
                this.thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }

        val hasNextPage = doc.select("a.pageNav-jump--next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.p-title-value")?.text()
            ?.replace(Regex("\\s*ES\\s*"), "")
            ?.replace(Regex("\\s*Manhua\\s*"), "")
            ?.replace(Regex("\\s*Manga\\s*"), "")
            ?.replace(Regex("\\s*Webtoon\\s*"), "")
            ?.replace(Regex("\\s*Comic\\s*"), "")
            ?.replace(Regex("\\s*Manhwa\\s*"), "")
            ?.trim() ?: ""
        thumbnail_url = document.selectFirst("img[alt='Resource banner']")?.attr("src")

        val altTitles = document.select("dl.pairs--customField[data-field='titulos_alternativo'] dd")
            .firstOrNull()?.html()?.split("<br>")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
        var desc = ""
        if (!altTitles.isNullOrEmpty()) {
            desc = "Títulos alternativos: ${altTitles.joinToString(", ")}\n\n"
        }

        val mainDesc = document.selectFirst(".bbWrapper")?.let { wrapper ->
            val html = wrapper.html()
            // Find the text before the first <br> or <h3> tag
            val endIndex = minOf(
                html.indexOf("<br"),
                html.indexOf("<h3"),
            ).let { if (it == -1) html.length else it }

            // Extract just the first paragraph and clean it
            html.substring(0, endIndex).replace(Regex("<[^>]*>"), "").trim()
        }
        if (!mainDesc.isNullOrEmpty()) {
            desc += mainDesc
        }
        description = desc

        genre = document.select("dd .tagItem").joinToString { it.text().trim() }

        // Status parsing
        val statusText = document.select("dl.pairs--customField[data-field='status'] dd").text().trim()
        status = when {
            statusText.contains("Publicándose", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Terminada", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Cancelada", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return chapterListRequest(manga, 1)
    }

    // Método para solicitar capítulos con paginación
    private fun chapterListRequest(manga: SManga, page: Int): Request {
        return GET("$baseUrl${manga.url}capitulos?page=$page", headers)
    }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a.md-chapter-link")
        setUrlWithoutDomain(link?.attr("href") ?: "")
        name = link?.text()?.trim() ?: ""

        // Leer la fecha directamente del nuevo elemento md-grid-date
        val dateText = element.selectFirst(".md-grid-date")
            ?.ownText()
            ?.trim()
        date_upload = dateText?.let { parseDateSimple(it) } ?: 0L
    }

    private fun parseDateSimple(date: String): Long {
        // Intentar con formato dd/MM/yyyy de la página
        // En caso de que venga con texto extra, extraer solo la fecha
        val cleanDate = Regex("""\d{2}/\d{2}/\d{4}""").find(date)?.value ?: return 0L
        return try {
            SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).parse(cleanDate)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = org.jsoup.Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()

        // Obtener capítulos de la página actual
        val currentPageChapters = document.select(chapterListSelector()).map { element ->
            chapterFromElement(element)
        }
        chapters.addAll(currentPageChapters)

        // Verificar si hay más páginas y cargar todos los capítulos
        var currentPage = 1
        var hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null

        // Obtener la URL base del manga desde la respuesta actual
        val currentUrl = response.request.url.toString()
        val mangaUrl = currentUrl.substringBefore("capitulos")
        val manga = SManga.create().apply {
            setUrlWithoutDomain(mangaUrl.removePrefix(baseUrl))
        }

        while (hasNextPage) {
            currentPage++
            try {
                val nextPageResponse = client.newCall(chapterListRequest(manga, currentPage)).execute()
                val nextPageDocument = org.jsoup.Jsoup.parse(nextPageResponse.body.string())
                nextPageResponse.close()

                val nextPageChapters = nextPageDocument.select(chapterListSelector()).map { element ->
                    chapterFromElement(element)
                }

                if (nextPageChapters.isNotEmpty()) {
                    chapters.addAll(nextPageChapters)
                    hasNextPage = nextPageDocument.selectFirst(popularMangaNextPageSelector()) != null
                } else {
                    hasNextPage = false
                }
            } catch (e: Exception) {
                // Si hay error cargando la página, detener la paginación
                hasNextPage = false
            }
        }

        // Retornar capítulos en orden reverso (más recientes primero)
        return chapters.reversed()
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.avmReader-page:not(.avmReader-page--end) img.js-avmImage")
            .mapIndexed { i, img ->
                val url = img.attr("data-src").ifEmpty { img.attr("src") }
                Page(i, "", baseUrl + url)
            }
    }

    override fun imageUrlParse(document: Document): String = ""
}
