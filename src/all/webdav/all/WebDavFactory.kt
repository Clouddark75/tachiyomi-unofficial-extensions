package eu.kanade.tachiyomi.extension.all.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class WebDavFactory : ConfigurableSource, HttpSource() {

    override val name = "WebDAV"
    override val baseUrl = ""
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", 0x0000)
    }

    private lateinit var context: Context

    fun initialize(context: Context) {
        this.context = context
    }

    private val webDavSource: WebDavSource by lazy {
        WebDavSource(
            baseUrl = preferences.getString(SERVER_URL_PREF, "") ?: "",
            username = preferences.getString(USERNAME_PREF, ""),
            password = preferences.getString(PASSWORD_PREF, "")
        )
    }

    override fun popularMangaRequest(page: Int): Request = throw Exception("Not implemented")
    override fun popularMangaParse(response: Response): MangasPage = throw Exception("Not implemented")
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not implemented")
    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Not implemented")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw Exception("Not implemented")
    override fun searchMangaParse(response: Response): MangasPage = throw Exception("Not implemented")
    override fun mangaDetailsRequest(manga: SManga): Request = throw Exception("Not implemented")
    override fun mangaDetailsParse(response: Response): SManga = throw Exception("Not implemented")
    override fun chapterListRequest(manga: SManga): Request = throw Exception("Not implemented")
    override fun chapterListParse(response: Response): List<SChapter> = throw Exception("Not implemented")
    override fun pageListRequest(chapter: SChapter): Request = throw Exception("Not implemented")
    override fun pageListParse(response: Response): List<eu.kanade.tachiyomi.source.model.Page> = throw Exception("Not implemented")
    override fun imageUrlRequest(page: eu.kanade.tachiyomi.source.model.Page): Request = throw Exception("Not implemented")
    override fun imageUrlParse(response: Response): String = throw Exception("Not implemented")

    // Implementación usando WebDavSource
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val mangaList = webDavSource.fetchMangaList()
            val sMangaList = mangaList.map { mangaInfo ->
                SManga.create().apply {
                    title = mangaInfo.title
                    url = mangaInfo.path
                    thumbnail_url = mangaInfo.thumbnailUrl
                    description = mangaInfo.description
                }
            }
            MangasPage(sMangaList, false)
        }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.just(manga)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val mangaInfo = MangaInfo(manga.title, manga.url)
            val chapters = webDavSource.fetchChapters(mangaInfo)
            
            chapters.map { chapterInfo ->
                SChapter.create().apply {
                    name = chapterInfo.name
                    url = chapterInfo.path
                    date_upload = chapterInfo.dateUpload
                    chapter_number = chapterInfo.chapterNumber
                }
            }
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<eu.kanade.tachiyomi.source.model.Page>> {
        return Observable.fromCallable {
            // Necesitamos obtener el manga desde el capítulo
            // Esto es una simplificación, en una implementación real necesitarías más context
            val mangaInfo = MangaInfo("", "") // Placeholder
            val chapterInfo = ChapterInfo(chapter.name, chapter.url)
            val pages = webDavSource.fetchPageList(mangaInfo, chapterInfo)
            
            pages.map { page ->
                eu.kanade.tachiyomi.source.model.Page(page.index, page.imageUrl)
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverUrlPref = EditTextPreference(screen.context).apply {
            key = SERVER_URL_PREF
            title = "Server URL"
            summary = "WebDAV server URL (e.g., https://example.com/webdav)"
            setDefaultValue("")
            dialogTitle = title
            
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue as String
                url.isNotBlank()
            }
        }
        
        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary = "Username for WebDAV authentication (optional)"
            setDefaultValue("")
            dialogTitle = title
        }
        
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Password for WebDAV authentication (optional)"
            setDefaultValue("")
            dialogTitle = title
            
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
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
