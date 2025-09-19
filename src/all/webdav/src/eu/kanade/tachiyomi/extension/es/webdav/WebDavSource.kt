package eu.example.tachiyomi.webdav

import okhttp3.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder

interface CatalogueSource {
    val name: String
    fun fetchMangaList(): List<MangaInfo>
    fun fetchChapters(manga: MangaInfo): List<ChapterInfo>
    fun fetchPageList(manga: MangaInfo, chapter: ChapterInfo): List<Page>
}

data class MangaInfo(val title: String, val path: String)
data class ChapterInfo(val name: String, val path: String)
data class Page(val index: Int, val url: String)

class WebDavSource(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null
) : CatalogueSource {

    override val name: String = "WebDAV"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (username == null || password == null) return null
                val credential = Credentials.basic(username, password)
                return response.request.newBuilder()
                    .header("Authorization", credential)
                    .build()
            }
        })
        .build()

    override fun fetchMangaList(): List<MangaInfo> {
        val xml = propfind(baseUrl)
        return parsePropfindList(xml, baseUrl)
    }

    override fun fetchChapters(manga: MangaInfo): List<ChapterInfo> {
        val url = joinUrl(baseUrl, manga.path)
        val xml = propfind(url)
        val entries = parsePropfindList(xml, url)

        val chapters = mutableListOf<ChapterInfo>()

        for (entry in entries) {
            if (isArchive(entry.title)) {
                val name = entry.title.removeSuffix(".cbz").removeSuffix(".zip")
                chapters.add(ChapterInfo(name, entry.path))
            } else if (!isImage(entry.title)) {
                chapters.add(ChapterInfo(entry.title, entry.path))
            }
        }
        return chapters
    }

    override fun fetchPageList(manga: MangaInfo, chapter: ChapterInfo): List<Page> {
        val url = joinUrl(baseUrl, chapter.path)

        return if (isArchive(chapter.path)) {
            listOf(Page(1, url))
        } else {
            val xml = propfind(url)
            val entries = parsePropfindList(xml, url)
            val images = entries.filter { isImage(it.title) }.sortedBy { it.title }
            images.mapIndexed { idx, e -> Page(idx + 1, joinUrl(baseUrl, e.path)) }
        }
    }

    private fun propfind(url: String, depth: Int = 1): String {
        val reqBody = "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\\"> <allprop/></propfind>"
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", RequestBody.create(MediaType.parse("application/xml"), reqBody))
            .header("Depth", depth.toString())
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("PROPFIND failed: ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun parsePropfindList(xml: String, base: String): List<MangaInfo> {
        val list = mutableListOf<MangaInfo>()
        if (xml.isBlank()) return list

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        var inHref = false
        var currentHref: String? = null
        var currentDisplayName: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase()
                    if (name == "href") inHref = true
                    if (name == "displayname") {
                        currentDisplayName = parser.nextText()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inHref) {
                        currentHref = parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name?.lowercase()
                    if (name == "href") inHref = false
                    if (name == "response") {
                        if (currentHref != null) {
                            val rel = toRelativePath(base, currentHref)
                            val title = currentDisplayName ?: lastSegment(rel)
                            list.add(MangaInfo(title, rel))
                        }
                        currentHref = null
                        currentDisplayName = null
                    }
                }
            }
            event = parser.next()
        }

        return list.distinctBy { it.path }
            .filter { it.path != "" }
    }

    private fun toRelativePath(base: String, href: String): String {
        return try {
            val b = base.trimEnd('/')
            val h = href.trimEnd('/')
            if (h.startsWith(b)) {
                h.substring(b.length).trimStart('/').let { if (it.isEmpty()) "." else it }
            } else {
                lastSegment(h)
            }
        } catch (e: Exception) {
            lastSegment(href)
        }
    }

    private fun lastSegment(path: String): String {
        return path.trimEnd('/').split('/').lastOrNull() ?: path
    }

    private fun joinUrl(base: String, path: String): String {
        if (path == ".") return base
        return base.trimEnd('/') + "/" + URLEncoder.encode(path, "UTF-8").replace("+", "%20")
    }

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif")
    }

    private fun isArchive(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".cbz") || n.endsWith(".zip")
    }
}
