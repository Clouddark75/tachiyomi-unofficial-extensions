package eu.kanade.tachiyomi.extension.es.animebbg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.OffsetDateTime

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

    override fun chapterListSelector(): String = "div.structItem-title a"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Si hay filtros de tipo o género, usar navegación directa en lugar de búsqueda
        val typeFilter = filters.findInstance<TypeFilter>()
        val genreFilter = filters.findInstance<GenreFilter>()

        if (typeFilter != null && typeFilter.state != 0) {
            val typeUrl = when (typeFilter.state) {
                1 -> "$baseUrl/comics/ct/manga.130/"
                2 -> "$baseUrl/comics/ct/manhua.132/"
                3 -> "$baseUrl/comics/ct/manhwa.131/"
                else -> "$baseUrl/comics/"
            }
            return GET("$typeUrl?page=$page", headers)
        }

        if (genreFilter != null && genreFilter.state != 0) {
            val genreUrl = "$baseUrl/tags/${getGenreList()[genreFilter.state].second}/"
            return GET("$genreUrl?page=$page", headers)
        }

        // Si hay query de búsqueda, usar Google Custom Search
        if (query.isNotEmpty()) {
            val searchId = "198660"
            val url = "$baseUrl/search/$searchId/".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("o", "date")
                fragment("gsc.tab=0&gsc.q=$query&gsc.page=$page")
            }
            return GET(url.build(), headers)
        }

        // Por defecto, mostrar todos los comics
        return GET("$baseUrl/comics/?page=$page", headers)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.text().trim()
        }

        // Cargar página del manga para obtener el thumbnail
        try {
            val response = client.newCall(GET(baseUrl + manga.url, headers)).execute()
            response.use {
                val detailsDoc = org.jsoup.Jsoup.parse(it.body.string())
                manga.thumbnail_url = detailsDoc.selectFirst("img[alt='Resource banner']")?.attr("src")
            }
        } catch (e: Exception) {
            manga.thumbnail_url = ""
        }

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
                ?.replace(Regex("\\s*-\\s*AnimeBBG.*"), "")
                ?.trim() ?: ""

            // Intentar obtener thumbnail de la página de detalles si es necesario
            try {
                val response = client.newCall(GET(baseUrl + manga.url, headers)).execute()
                response.use {
                    val detailsDoc = org.jsoup.Jsoup.parse(it.body.string())
                    manga.thumbnail_url = detailsDoc.selectFirst("img[alt='Resource banner']")?.attr("src")
                }
            } catch (e: Exception) {
                // Si falla, continuar sin thumbnail
                manga.thumbnail_url = ""
            }
        }

        return manga
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = org.jsoup.Jsoup.parse(response.body.string())
        val url = response.request.url.toString()

        // Si es una búsqueda de Google Custom Search
        if (url.contains("/search/")) {
            // Filtrar resultados para incluir solo comics y excluir capítulos/updates/temas
            val mangas = document.select(".cse-result").mapNotNull { element ->
                val linkElement = element.selectFirst("a")
                val href = linkElement?.attr("href") ?: ""

                // Filtrar solo enlaces que sean de comics y no de capítulos, updates o temas
                if (href.contains("/comics/") &&
                    !href.contains("/update/") &&
                    !href.contains("/capitulo") &&
                    !href.contains("/temas/") &&
                    !href.contains("Capítulo") &&
                    !href.contains("Capitulo")
                ) {
                    try {
                        searchMangaFromElement(element)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }.filter { manga -> manga.title.isNotEmpty() }

            val hasNextPage = document.selectFirst("a[aria-label='Go to the next page']") != null
            return MangasPage(mangas, hasNextPage)
        }

        // Si es navegación por filtros (tipo o género), usar el selector normal
        return popularMangaParse(response)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Nota: Los filtros no se pueden combinar con búsqueda de texto"),
        Filter.Separator(),
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter : Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "Manga", "Manhua", "Manhwa"),
    )

    private class GenreFilter : Filter.Select<String>(
        "Género",
        getGenreNames(),
    )

    companion object {
        private fun getGenreNames() = listOf(
            "Todos",
            "Acción",
            "Recuentos de la vida",
            "Aventura",
            "Comedia",
            "Drama",
            "Fantasía",
            "Magia",
            "Webcomic",
            "Harem",
            "Reencarnación",
            "Ciencia ficción",
            "Supervivencia",
        ).toTypedArray()
    }

    private fun getGenreList() = listOf(
        "Todos" to "",
        "Acción" to "accion",
        "Recuentos de la vida" to "recuentos-de-la-vida",
        "Aventura" to "aventura",
        "Comedia" to "comedia",
        "Drama" to "drama",
        "Fantasía" to "fantasia",
        "Magia" to "magia",
        "Webcomic" to "webcomic",
        "Harem" to "harem",
        "Reencarnación" to "reencarnacion",
        "Ciencia ficción" to "ciencia-ficcion",
        "Supervivencia" to "supervivencia",
    )

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.p-title-value")?.text()
            ?.replace(Regex("\\s*ES\\s*"), "")
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
        return GET("$baseUrl${manga.url}capitulos", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var currentPage = 1
        var hasNextPage = true

        // Parsear la primera página (ya tenemos la respuesta)
        val document = response.asJsoup()
        allChapters.addAll(document.select(chapterListSelector()).map { chapterFromElement(it) })

        // Verificar si hay más páginas
        hasNextPage = document.selectFirst("a.pageNavSimple-el--next") != null

        // Si hay más páginas, obtener todas
        while (hasNextPage) {
            currentPage++
            val nextPageUrl = "${response.request.url}?page=$currentPage"
            
            try {
                val nextResponse = client.newCall(GET(nextPageUrl, headers)).execute()
                nextResponse.use {
                    val nextDocument = it.asJsoup()
                    val chaptersOnPage = nextDocument.select(chapterListSelector()).map { element -> 
                        chapterFromElement(element) 
                    }
                    
                    if (chaptersOnPage.isNotEmpty()) {
                        allChapters.addAll(chaptersOnPage)
                        hasNextPage = nextDocument.selectFirst("a.pageNavSimple-el--next") != null
                    } else {
                        hasNextPage = false
                    }
                }
            } catch (e: Exception) {
                hasNextPage = false
            }
        }

        return allChapters.reversed()
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("a.js-lbImage").mapIndexed { i, element ->
            val url = element.attr("data-src").ifEmpty {
                element.selectFirst("img")?.attr("src") ?: ""
            }
            Page(i, "", baseUrl + url)
        }
    }

    override fun imageUrlParse(document: Document): String = ""
}
