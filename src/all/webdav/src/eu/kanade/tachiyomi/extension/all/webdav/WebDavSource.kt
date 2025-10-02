package eu.kanade.tachiyomi.extension.all.webdav

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

interface CatalogueSource {
    val name: String
    val id: Long
    val lang: String
    fun fetchMangaList(): List<MangaInfo>
    fun fetchChapters(manga: MangaInfo): List<ChapterInfo>
    fun fetchPageList(manga: MangaInfo, chapter: ChapterInfo): List<WebDavPage>
    fun fetchImageUrl(page: WebDavPage): String
}

data class MangaInfo(
    val title: String,
    val path: String,
    val url: String = path,
    val description: String? = null,
    val thumbnailUrl: String? = null,
)

data class ChapterInfo(
    val name: String,
    val path: String,
    val url: String = path,
    val dateUpload: Long = System.currentTimeMillis(),
    val chapterNumber: Float = -1f,
)

data class WebDavPage(
    val index: Int,
    val imageUrl: String,
)

class WebDavSource(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
) : CatalogueSource {

    override val name: String = "WebDAV"
    override val id: Long = baseUrl.hashCode().toLong()
    override val lang: String = "all"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                authenticator(
                    object : Authenticator {
                        override fun authenticate(route: Route?, response: Response): Request? {
                            if (response.request.header("Authorization") != null) {
                                return null // Ya intentamos autenticar
                            }
                            val credential = Credentials.basic(username, password)
                            return response.request.newBuilder()
                                .header("Authorization", credential)
                                .build()
                        }
                    },
                )
            }
        }
        .build()

    override fun fetchMangaList(): List<MangaInfo> {
        if (baseUrl.isBlank()) {
            throw Exception("Server URL is not configured")
        }

        return try {
            val xml = propfind(baseUrl)
            val entries = parsePropfindList(xml, baseUrl)

            entries.filter { entry ->
                !isFile(entry.title) && entry.path != baseUrl // Solo directorios como manga
            }.map { entry ->
                MangaInfo(
                    title = entry.title,
                    path = entry.path,
                    url = entry.path,
                    description = "WebDAV Manga: ${entry.title}",
                )
            }
        } catch (e: Exception) {
            throw Exception("Error fetching manga list: ${e.message}")
        }
    }

    override fun fetchChapters(manga: MangaInfo): List<ChapterInfo> {
        return try {
            val url = manga.path

            val xml = propfind(url)
            val entries = parsePropfindList(xml, url)

            val chapters = mutableListOf<ChapterInfo>()

            for (entry in entries) {
                if (entry.path == url) continue // Saltar directorio actual

                if (isArchive(entry.title)) {
                    // Archivo comprimido (CBZ, ZIP, etc.)
                    val name = entry.title
                        .removeSuffix(".cbz")
                        .removeSuffix(".zip")
                        .removeSuffix(".cbr")
                        .removeSuffix(".rar")
                        .removeSuffix(".cb7")
                        .removeSuffix(".7z")

                    val chapterNumber = extractChapterNumber(name)

                    chapters.add(
                        ChapterInfo(
                            name = name,
                            path = entry.path,
                            url = entry.path,
                            chapterNumber = chapterNumber,
                            dateUpload = System.currentTimeMillis(),
                        ),
                    )
                } else if (!isFile(entry.title)) {
                    // Directorio que podría contener imágenes
                    val chapterNumber = extractChapterNumber(entry.title)
                    chapters.add(
                        ChapterInfo(
                            name = entry.title,
                            path = entry.path,
                            url = entry.path,
                            chapterNumber = chapterNumber,
                            dateUpload = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            // Ordenar por número de capítulo (ascendente)
            chapters.sortedBy { chapter ->
                if (chapter.chapterNumber > 0) chapter.chapterNumber else Float.MAX_VALUE
            }
        } catch (e: Exception) {
            throw Exception("Error fetching chapters for ${manga.title}: ${e.message}")
        }
    }

    override fun fetchPageList(manga: MangaInfo, chapter: ChapterInfo): List<WebDavPage> {
        return try {
            val url = chapter.path

            if (isArchive(chapter.path) || isArchive(url)) {
                // Para archivos comprimidos, devolver el archivo como página única
                // Tachiyomi puede manejar CBZ/ZIP internamente
                listOf(WebDavPage(0, url))
            } else {
                // Para directorios, buscar imágenes
                val xml = propfind(url)
                val entries = parsePropfindList(xml, url)
                val images = entries
                    .filter { isImage(it.title) && it.path != url }
                    .sortedWith(naturalOrderComparator())

                if (images.isEmpty()) {
                    throw Exception("No images found in chapter: ${chapter.name}")
                }

                images.mapIndexed { idx, entry ->
                    WebDavPage(idx, entry.path)
                }
            }
        } catch (e: Exception) {
            throw Exception("Error fetching pages for ${chapter.name}: ${e.message}")
        }
    }

    override fun fetchImageUrl(page: WebDavPage): String {
        return page.imageUrl
    }

    private fun propfind(url: String, depth: Int = 1): String {
        val reqBody = """
<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
    <D:allprop/>
</D:propfind>
        """.trimIndent()

        val requestBuilder = Request.Builder()
            .url(url)
            .method("PROPFIND", RequestBody.create("application/xml; charset=utf-8".toMediaTypeOrNull(), reqBody))
            .header("Depth", depth.toString())
            .header("Content-Type", "application/xml; charset=utf-8")
            .header("User-Agent", "Tachiyomi WebDAV Extension")

        // Agregar autenticación si está disponible
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            val credential = Credentials.basic(username, password)
            requestBuilder.header("Authorization", credential)
        }

        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("PROPFIND failed: ${response.code} - ${response.message}")
            }
            return response.body?.string() ?: ""
        }
    }

    @Suppress("LongMethod", "ComplexMethod")
    private fun parsePropfindList(xml: String, currentUrl: String): List<MangaInfo> {
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
            var inDisplayName = false

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase()
                        when (name) {
                            "href" -> {
                                inHref = true
                                currentHref = null
                            }
                            "displayname" -> {
                                inDisplayName = true
                                currentDisplayName = null
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inHref && parser.text.isNotBlank()) {
                            currentHref = parser.text.trim()
                        } else if (inDisplayName && parser.text.isNotBlank()) {
                            currentDisplayName = parser.text.trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase()
                        when (name) {
                            "href" -> inHref = false
                            "displayname" -> inDisplayName = false
                            "response" -> {
                                if (currentHref != null) {
                                    try {
                                        // NO decodificar - mantener el encoding del servidor
                                        val href = currentHref!!

                                        // Construir la URL completa manteniendo el encoding
                                        val fullUrl = buildFullUrl(currentUrl, href)

                                        // Evitar incluir el directorio actual
                                        val normalizedCurrent = currentUrl.trimEnd('/')
                                        val normalizedFull = fullUrl.trimEnd('/')

                                        if (normalizedFull != normalizedCurrent && !normalizedFull.equals(normalizedCurrent, ignoreCase = true)) {
                                            // Decodificar solo para el título de display
                                            val decodedHref = try {
                                                URLDecoder.decode(href, "UTF-8")
                                            } catch (e: Exception) {
                                                href
                                            }
                                            val title = currentDisplayName ?: lastSegment(decodedHref)

                                            if (title.isNotEmpty()) {
                                                list.add(MangaInfo(title, fullUrl))
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Ignorar entradas problemáticas
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
            throw Exception("Error parsing WebDAV response: ${e.message}")
        }

        return list.distinctBy { it.path }
    }

    private fun buildFullUrl(baseUrl: String, href: String): String {
        return try {
            // Si el href ya es una URL completa, usarla directamente
            if (href.startsWith("http://") || href.startsWith("https://")) {
                return href
            }
            
            // Si el href es absoluto (empieza con /), construir desde el dominio
            if (href.startsWith("/")) {
                val uri = URI(baseUrl)
                return "$uri.scheme://$uri.authority$href"
            }

            // Si es relativo, añadir al final de baseUrl
            "${baseUrl.trimEnd('/')}/${href.trimStart('/')}"
        } catch (e: Exception) {
            // En caso de error, intentar una concatenación simple
            baseUrl.trimEnd('/') + "/" + href.trimStart('/')
        }
    }

    private fun lastSegment(path: String): String {
        return path.trimEnd('/').split('/').lastOrNull()?.takeIf { it.isNotEmpty() } ?: path
    }

    private fun isImage(name: String): Boolean {
        val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff", "avif", "svg")
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
            Regex("""(?:chapter|ch|cap|c)[\s_-]*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+(?:[.,]\d+)?)"""),
            Regex("""vol\s*\d+\s*(?:chapter|ch|cap|c)[\s_-]*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE),
        )

        for (pattern in patterns) {
            val match = pattern.find(name)
            if (match != null) {
                val numberStr = match.groupValues[1].replace(',', '.')
                return numberStr.toFloatOrNull() ?: -1f
            }
        }
        return -1f
    }

    private fun naturalOrderComparator(): Comparator<MangaInfo> {
        return Comparator { a, b ->
            val regex = Regex("""(\d+)""")
            val aNumbers = regex.findAll(a.title).map { it.value.toIntOrNull() ?: 0 }.toList()
            val bNumbers = regex.findAll(b.title).map { it.value.toIntOrNull() ?: 0 }.toList()

            for (i in 0 until min(aNumbers.size, bNumbers.size)) {
                val comparison = aNumbers[i].compareTo(bNumbers[i])
                if (comparison != 0) return@Comparator comparison
            }

            when {
                aNumbers.size > bNumbers.size -> 1
                aNumbers.size < bNumbers.size -> -1
                else -> a.title.compareTo(b.title, ignoreCase = true)
            }
        }
    }
}
