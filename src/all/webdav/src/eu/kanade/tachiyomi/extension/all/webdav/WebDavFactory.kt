package eu.kanade.tachiyomi.extension.all.webdav

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class WebDavFactory : ConfigurableSource, HttpSource() {

    override val name = "WebDAV"
    override val baseUrl get() = preferences.getString(SERVER_URL_PREF, "") ?: ""
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val webDavSource: WebDavSource by lazy {
        WebDavSource(
            baseUrl = preferences.getString(SERVER_URL_PREF, "") ?: "",
            username = preferences.getString(USERNAME_PREF, "") ?: "",
            password = preferences.getString(PASSWORD_PREF, "") ?: "",
        )
    }

    // Implementaciones requeridas por HttpSource (con excepciones ya que no las usamos)
    override fun popularMangaRequest(page: Int): Request = throw Exception("Use fetchPopularManga instead")
    override fun popularMangaParse(response: Response): MangasPage = throw Exception("Use fetchPopularManga instead")
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not supported")
    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Not supported")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw Exception("Use fetchSearchManga instead")
    override fun searchMangaParse(response: Response): MangasPage = throw Exception("Use fetchSearchManga instead")
    override fun mangaDetailsRequest(manga: SManga): Request = throw Exception("Use fetchMangaDetails instead")
    override fun mangaDetailsParse(response: Response): SManga = throw Exception("Use fetchMangaDetails instead")
    override fun chapterListRequest(manga: SManga): Request = throw Exception("Use fetchChapterList instead")
    override fun chapterListParse(response: Response): List<SChapter> = throw Exception("Use fetchChapterList instead")
    override fun pageListRequest(chapter: SChapter): Request = throw Exception("Use fetchPageList instead")
    override fun pageListParse(response: Response): List<Page> = throw Exception("Use fetchPageList instead")
    override fun imageUrlRequest(page: Page): Request = throw Exception("Use fetchImageUrl instead")
    override fun imageUrlParse(response: Response): String = throw Exception("Use fetchImageUrl instead")

    // Implementaciones usando Observable que realmente se usan
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        if (baseUrl.isEmpty()) {
            return Observable.error(Exception("Server URL not configured. Please configure in settings."))
        }

        return Observable.fromCallable {
            try {
                val mangaList = webDavSource.fetchMangaList()
                val sMangaList = mangaList.map { mangaInfo ->
                    SManga.create().apply {
                        title = mangaInfo.title
                        url = mangaInfo.path
                        thumbnail_url = mangaInfo.thumbnailUrl
                        description = mangaInfo.description
                        status = SManga.UNKNOWN
                        initialized = true
                    }
                }
                MangasPage(sMangaList, false)
            } catch (e: Exception) {
                throw Exception("Failed to fetch manga list: ${e.message}")
            }
        }
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (baseUrl.isEmpty()) {
            return Observable.error(Exception("Server URL not configured. Please configure in settings."))
        }

        return Observable.fromCallable {
            try {
                val mangaList = webDavSource.fetchMangaList()
                val filteredList = if (query.isNotBlank()) {
                    mangaList.filter { it.title.contains(query, ignoreCase = true) }
                } else {
                    mangaList
                }

                val sMangaList = filteredList.map { mangaInfo ->
                    SManga.create().apply {
                        title = mangaInfo.title
                        url = mangaInfo.path
                        thumbnail_url = mangaInfo.thumbnailUrl
                        description = mangaInfo.description
                        status = SManga.UNKNOWN
                        initialized = true
                    }
                }
                MangasPage(sMangaList, false)
            } catch (e: Exception) {
                throw Exception("Failed to search manga: ${e.message}")
            }
        }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            try {
                // Intentar obtener más detalles del manga si es posible
                val mangaInfo = MangaInfo(manga.title, manga.url)
                val chapters = webDavSource.fetchChapters(mangaInfo)

                manga.apply {
                    status = SManga.UNKNOWN
                    description = description ?: "Chapters found: ${chapters.size}"
                    initialized = true
                }
            } catch (e: Exception) {
                // Si hay error, devolver el manga tal como está
                manga.apply {
                    status = SManga.UNKNOWN
                    initialized = true
                }
            }
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            try {
                val mangaInfo = MangaInfo(manga.title, manga.url)
                val chapters = webDavSource.fetchChapters(mangaInfo)

                chapters.mapIndexed { index, chapterInfo ->
                    SChapter.create().apply {
                        name = chapterInfo.name
                        url = chapterInfo.path
                        date_upload = chapterInfo.dateUpload
                        chapter_number = if (chapterInfo.chapterNumber > 0) {
                            chapterInfo.chapterNumber
                        } else {
                            (chapters.size - index).toFloat()
                        }
                    }
                }.reversed() // Mostrar capítulos más recientes primero
            } catch (e: Exception) {
                throw Exception("Failed to fetch chapters for ${manga.title}: ${e.message}")
            }
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            try {
                // Crear objetos temporales para la consulta
                val mangaInfo = MangaInfo("", "") // Placeholder, WebDavSource no lo necesita realmente
                val chapterInfo = ChapterInfo(chapter.name, chapter.url)
                val pages = webDavSource.fetchPageList(mangaInfo, chapterInfo)

                pages.map { page ->
                    Page(page.index, page.imageUrl)
                }
            } catch (e: Exception) {
                throw Exception("Failed to fetch pages for ${chapter.name}: ${e.message}")
            }
        }
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.fromCallable {
            webDavSource.fetchImageUrl(WebDavPage(page.index, page.imageUrl))
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverUrlPref = EditTextPreference(screen.context).apply {
            key = SERVER_URL_PREF
            title = "Server URL"
            summary = "WebDAV server URL (e.g., https://example.com/webdav/)"
            setDefaultValue("")
            dialogTitle = "Server URL"

            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue.toString()
                url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
            }
        }

        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary = "Username for WebDAV authentication (leave empty if not needed)"
            setDefaultValue("")
            dialogTitle = "Username"
        }

        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Password for WebDAV authentication (leave empty if not needed)"
            setDefaultValue("")
            dialogTitle = "Password"

            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        screen.addPreference(serverUrlPref)
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }

    companion object {
        private const val SERVER_URL_PREF = "server_url"
        private const val USERNAME_PREF = "username"
        private const val PASSWORD_PREF = "password"
    }
}
