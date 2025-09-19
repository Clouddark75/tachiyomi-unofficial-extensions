package eu.kanade.tachiyomi.extension.all.webdav

import okhttp3.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

interface CatalogueSource {
    val name: String
    val id: Long
    val lang: String
    fun fetchMangaList(): List<MangaInfo>
    fun fetchChapters(manga: MangaInfo): List<ChapterInfo>
    fun fetchPageList(manga: MangaInfo, chapter: ChapterInfo): List<Page>
    fun fetchImageUrl(page: Page): String
}

data class MangaInfo(
    val title: String, 
    val path: String,
    val url: String = path,
    val description: String? = null,
    val thumbnailUrl: String? = null
)

data class ChapterInfo(
    val name: String, 
    val path: String,
    val url: String = path,
    val dateUpload: Long = System.currentTimeMillis(),
    val chapterNumber: Float = -1f
)

data class Page(val index: Int, val imageUrl: String)

class WebDavSource(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null
) : CatalogueSource {

    override val name: String = "WebDAV"
    override val id: Long = baseUrl.hashCode().toLong()
    override val lang: String = "all"

    private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
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
        return try {
            val xml = propfind(baseUrl)
            parsePropfindList(xml, baseUrl)
                    .filter { !isFile(it.title) } // Solo directorios como manga
                    .map { entry ->
                        MangaInfo(
                            title = entry.title,
                            path = entry.path,
                            url = entry.path
                        )
                    }
        } catch (e: Exception) {
            println("Error fetching manga list: ${e.message}")
            emptyList()
        }
    }

    override fun fetchChapters(manga: MangaInfo): List<ChapterInfo> {
        return try {
            val url = joinUrl(baseUrl, manga.path)
            val xml = propfind(url)
            val entries = parsePropfindList(xml, url)

            val chapters = mutableListOf<ChapterInfo>()

            for (entry in entries.sortedBy { it.title }) {
                if (isArchive(entry.title)) {
                    val name = entry.title
                            .removeSuffix(".cbz")
                            .removeSuffix(".zip")
                            .removeSuffix(".cbr")
                            .removeSuffix(".rar")
                    
                    val chapterNumber = extractChapterNumber(name)
                    
                    chapters.add(ChapterInfo(
                        name = name,
                        path = entry.path,
                        url = entry.path,
                        chapterNumber = chapterNumber
                    ))
                } else if (!isFile(entry.title)) {
                    // Es un directorio, podría contener imágenes
                    val chapterNumber = extractChapterNumber(entry.title)
                    chapters.add(ChapterInfo(
                        name = entry.title,
                        path = entry.path,
                        url = entry.path,
                        chapterNumber = chapterNumber
                    ))
                }
            }
            
            // Ordenar por número de capítulo
            chapters.sortedBy { it.chapterNumber }
        } catch (e: Exception) {
            println("Error fetching chapters for ${manga.title}: ${e.message}")
            emptyList()
        }
    }

    override fun fetchPageList(manga: MangaInfo, chapter: ChapterInfo): List<Page> {
        return try {
            val url = joinUrl(baseUrl, chapter.path)

            if (isArchive(chapter.path)) {
                // Para archivos, devolver el archivo como página única
                listOf(Page(1, url))
            } else {
                // Para directorios, buscar imágenes
                val xml = propfind(url)
                val entries = parsePropfindList(xml, url)
                val images = entries
                        .filter { isImage(it.title) }
                        .sortedWith(naturalOrderComparator())
                
                images.mapIndexed { idx, entry -> 
                    Page(idx + 1, joinUrl(baseUrl, entry.path))
                }
            }
        } catch (e: Exception) {
            println("Error fetching pages for ${chapter.name}: ${e.message}")
            emptyList()
        }
    }

    override fun fetchImageUrl(page: Page): String {
        return page.imageUrl
    }

    private fun propfind(url: String, depth: Int = 1): String {
        val reqBody = """<?xml version="1.0"?>
            <propfind xmlns="DAV:">
                <allprop/>
            </propfind>""".trimIndent()
            
        val request = Request.Builder()
                .url(url)
                .method("PROPFIND", RequestBody.create(MediaType.parse("application/xml"), reqBody))
                .header("Depth", depth.toString())
                .header("Content-Type", "application/xml")
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("PROPFIND failed: ${response.code} - ${response.message}")
            }
            return response.body?.string() ?: ""
        }
    }

    private fun parsePropfindList(xml: String, base: String): List<MangaInfo> {
        val list = mutableListOf<MangaInfo>()
        if (xml.isBlank()) return list

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
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
                        when (name) {
                            "href" -> inHref = true
                            "displayname" -> {
                                if (parser.next() == XmlPullParser.TEXT) {
                                    currentDisplayName = parser.text
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inHref && parser.text.isNotBlank()) {
                            currentHref = URLDecoder.decode(parser.text, "UTF-8")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase()
                        when (name) {
                            "href" -> inHref = false
                            "response" -> {
                                if (currentHref != null && !currentHref!!.endsWith(base.trimEnd('/'))) {
                                    val rel = toRelativePath(base, currentHref!!)
                                    val title = currentDisplayName ?: lastSegment(rel)
                                    if (rel.isNotEmpty() && rel != ".") {
                                        list.add(MangaInfo(title, rel))
                                    }
                                }
                                currentHref = null
                                currentDisplayName = null
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            println("Error parsing XML: ${e.message}")
        }

        return list.distinctBy { it.path }
    }

    private fun toRelativePath(base: String, href: String): String {
        return try {
            val normalizedBase = base.trimEnd('/')
            val normalizedHref = href.trimEnd('/')
            
            if (normalizedHref.startsWith(normalizedBase)) {
                val relative = normalizedHref.substring(normalizedBase.length).trimStart('/')
                if (relative.isEmpty()) "." else relative
            } else {
                lastSegment(normalizedHref)
            }
        } catch (e: Exception) {
            lastSegment(href)
        }
    }

    private fun lastSegment(path: String): String {
        return path.trimEnd('/').split('/').lastOrNull()?.takeIf { it.isNotEmpty() } ?: path
    }

    private fun joinUrl(base: String, path: String): String {
        if (path == "." || path.isEmpty()) return base
        val encodedPath = path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return base.trimEnd('/') + "/" + encodedPath
    }

    private fun isImage(name: String): Boolean {
        val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff", "avif")
        return extensions.any { name.lowercase().endsWith(".$it") }
    }

    private fun isArchive(name: String): Boolean {
        val extensions = setOf("cbz", "zip", "cbr", "rar", "cb7", "7z")
        return extensions.any { name.lowercase().endsWith(".$it") }
    }

    private fun isFile(name: String): Boolean {
        return isImage(name) || isArchive(name) || name.contains('.')
    }

    private fun extractChapterNumber(name: String): Float {
        // Buscar patrones comunes de numeración de capítulos
        val patterns = listOf(
            Regex("""(?:chapter|ch|cap)[\s_-]*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+(?:\.\d+)?)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(name)
            if (match != null) {
                return match.groupValues[1].toFloatOrNull() ?: -1f
            }
        }
        return -1f
    }

    private fun naturalOrderComparator(): Comparator<MangaInfo> {
        return Comparator { a, b ->
            val regex = Regex("""(\d+)""")
            val aNumbers = regex.findAll(a.title).map { it.value.toInt() }.toList()
            val bNumbers = regex.findAll(b.title).map { it.value.toInt() }.toList()
            
            for (i in 0 until minOf(aNumbers.size, bNumbers.size)) {
                val comparison = aNumbers[i].compareTo(bNumbers[i])
                if (comparison != 0) return@Comparator comparison
            }
            
            a.title.compareTo(b.title, ignoreCase = true)
        }
    }
}
