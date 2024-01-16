package eu.kanade.tachiyomi.extension.es.leercapitulo

import android.util.Base64
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.nio.charset.Charset

class LeerCapitulo : ParsedHttpSource() {
    override val name = "LeerCapitulo"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val baseUrl = "https://www.leercapitulo.com"

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaSelector(): String = ".hot-manga > .thumbnails > a"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")!!.imgAttr()
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search-autocomplete".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val mangas = json.decodeFromString<List<MangaDto>>(response.body.string()).map {
            SManga.create().apply {
                setUrlWithoutDomain(it.link)
                title = it.label
                thumbnail_url = baseUrl + it.thumbnail
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun searchMangaSelector(): String = throw UnsupportedOperationException()

    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException()

    override fun searchMangaNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesSelector(): String = ".mainpage-manga"

    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst(".media-body > a")!!.attr("abs:href"))
        title = element.selectFirst("h4")!!.text()
        thumbnail_url = element.selectFirst("img")!!.imgAttr()
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()

        val altNames = document.selectFirst(".description-update > span:contains(Títulos Alternativos:) + :matchText")?.text()
        val desc = document.selectFirst("#example2")!!.text()
        description = when (altNames) {
            null -> desc
            else -> "$desc\n\nAlt name(s): $altNames"
        }

        genre = document.select(".description-update a[href^='/genre/']").joinToString { it.text() }
        status = document.selectFirst(".description-update > span:contains(Estado:) + :matchText")!!.text().toStatus()
        thumbnail_url = document.selectFirst(".cover-detail > img")!!.imgAttr()
    }

    override fun chapterListSelector(): String = ".chapter-list > ul > li"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        with(element.selectFirst("a.xanh")!!) {
            setUrlWithoutDomain(attr("abs:href"))
            name = text()
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val orderList = document.selectFirst("meta[property=ad:check]")?.attr("content")
            ?.replace("[^\\d]+".toRegex(), "-")
            ?.split("-")

        val useReversedString = orderList?.any { it == "01" }

        val arrayData = document.selectFirst("#array_data")!!.text()

        val scriptUrl = document.selectFirst("section.bodycontainer > script[src$=.js]")?.attr("abs:src")
            ?: throw Exception("Script not found")

        val scriptData = client.newCall(GET(scriptUrl, headers)).execute().body.string()

        val deobfuscatedScript = Deobfuscator.deobfuscateScript(scriptData)
            ?: throw Exception("Unable to deobfuscate script")

        val keyRegex = """'([A-Z0-9]{62})'""".toRegex(RegexOption.IGNORE_CASE)

        val (key1, key2) = keyRegex.findAll(deobfuscatedScript).map { it.groupValues[1] }.toList()

        val encodedUrls = arrayData.replace(Regex("[A-Z0-9]", RegexOption.IGNORE_CASE)) {
            val index = key2.indexOf(it.value)
            key1[index].toString()
        }

        val urlList = String(Base64.decode(encodedUrls, Base64.DEFAULT), Charset.forName("UTF-8")).split(",")

        val sortedUrls = orderList?.map {
            if (useReversedString == true) urlList[it.reversed().toInt()] else urlList[it.toInt()]
        }?.reversed() ?: urlList

        return sortedUrls.mapIndexed { i, image_url ->
            Page(i, imageUrl = image_url)
        }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    private fun String.toStatus() = when (this) {
        "Ongoing" -> SManga.ONGOING
        "Paused" -> SManga.ON_HIATUS
        "Completed" -> SManga.COMPLETED
        "Cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    @Serializable
    data class MangaDto(
        val label: String,
        val link: String,
        val thumbnail: String,
        val value: String,
    )
}