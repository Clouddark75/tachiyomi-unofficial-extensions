package eu.kanade.tachiyomi.extension.all.hitomi

import android.app.Application
import android.content.SharedPreferences
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.util.Locale
import androidx.preference.CheckBoxPreference as AndroidXCheckBoxPreference
import androidx.preference.PreferenceScreen as AndroidXPreferenceScreen

/**
 * Ported from TachiyomiSy
 * Original work by NerdNumber9 for TachiyomiEH
 */

open class Hitomi(override val lang: String, private val nozomiLang: String) : HttpSource(), ConfigurableSource {

    override val supportsLatest = true

    override val name = if (nozomiLang == "all") "Hitomi.la unfiltered" else "Hitomi.la"

    override val id by lazy { if (lang == "all") 2703068117101782422 else super.id }

    override val baseUrl = BASE_URL

    private val json: Json by injectLazy()

    private var gg: String? = null
    private var common: String? = null

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", baseUrl)

    // Popular

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .flatMap { responseToMangas(it) }
    }

    override fun popularMangaRequest(page: Int) = HitomiNozomi.rangedGet(
        "$LTN_BASE_URL/popular-$nozomiLang.nozomi",
        100L * (page - 1),
        99L + 100 * (page - 1)
    )

    private fun responseToMangas(response: Response): Observable<MangasPage> {
        val range = response.header("Content-Range")!!
        val total = range.substringAfter('/').toLong()
        val end = range.substringBefore('/').substringAfter('-').toLong()
        val body = response.body!!
        return parseNozomiPage(body.bytes())
            .map {
                MangasPage(it, end < total - 1)
            }
    }

    private fun parseNozomiPage(array: ByteArray): Observable<List<SManga>> {
        val cursor = ByteCursor(array)
        val ids = (1..array.size / 4).map {
            cursor.nextInt()
        }

        return nozomiIdsToMangas(ids).toObservable()
    }

    private fun nozomiIdsToMangas(ids: List<Int>): Single<List<SManga>> {
        return Single.zip(
            ids.map { int ->
                client.newCall(GET("$LTN_BASE_URL/galleryblock/$int.html"))
                    .asObservable()
                    .subscribeOn(Schedulers.io()) // Perform all these requests in parallel
                    .map {
                        if (it.isSuccessful) {
                            parseGalleryBlock(it)
                        } else {
                            null
                        }
                    }
                    .toSingle()
            }
        ) { it.mapNotNull { m -> m as SManga? } }
    }

    private fun parseGalleryBlock(response: Response): SManga {
        var doc = response.body!!.string()

        getgg()
        getcommon()
        val duktape = QuickJs.create()

        duktape.evaluate("var document = {'location': {'hostname': 'ltn.hitomi.la'}};")
        duktape.evaluate(common!!)
        duktape.evaluate(gg!!)

        doc = duktape.evaluate("rewrite_tn_paths(`$doc`);") as String
        val document = Jsoup.parse(doc)

        val manga = SManga.create().apply {
            val titleElement = document.selectFirst("h1")
            title = titleElement.text()
            thumbnail_url = "https:" + if (useHqThumbPref()) {
                document.selectFirst("picture > source").attr("data-srcset").substringBefore(' ')
            } else {
                document.selectFirst("img").attr("data-src")
            }
            url = titleElement.child(0).attr("href")
        }

        duktape.close()
        return manga
    }

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException("Not used")

    // Latest

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .flatMap { responseToMangas(it) }
    }

    override fun latestUpdatesRequest(page: Int) = HitomiNozomi.rangedGet(
        "$LTN_BASE_URL/index-$nozomiLang.nozomi",
        100L * (page - 1),
        99L + 100 * (page - 1)
    )

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException("Not used")

    // Search

    private var cachedTagIndexVersion: Long? = null
    private var tagIndexVersionCacheTime: Long = 0
    private fun tagIndexVersion(): Single<Long> {
        val sCachedTagIndexVersion = cachedTagIndexVersion
        return if (sCachedTagIndexVersion == null ||
            tagIndexVersionCacheTime + INDEX_VERSION_CACHE_TIME_MS < System.currentTimeMillis()
        ) {
            HitomiNozomi.getIndexVersion(client, "tagindex").subscribeOn(Schedulers.io()).doOnNext {
                cachedTagIndexVersion = it
                tagIndexVersionCacheTime = System.currentTimeMillis()
            }.toSingle()
        } else {
            Single.just(sCachedTagIndexVersion)
        }
    }

    private var cachedGalleryIndexVersion: Long? = null
    private var galleryIndexVersionCacheTime: Long = 0
    private fun galleryIndexVersion(): Single<Long> {
        val sCachedGalleryIndexVersion = cachedGalleryIndexVersion
        return if (sCachedGalleryIndexVersion == null ||
            galleryIndexVersionCacheTime + INDEX_VERSION_CACHE_TIME_MS < System.currentTimeMillis()
        ) {
            HitomiNozomi.getIndexVersion(client, "galleriesindex").subscribeOn(Schedulers.io()).doOnNext {
                cachedGalleryIndexVersion = it
                galleryIndexVersionCacheTime = System.currentTimeMillis()
            }.toSingle()
        } else {
            Single.just(sCachedGalleryIndexVersion)
        }
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            val id = NOZOMI_ID_PATTERN.find(query.removePrefix(PREFIX_ID_SEARCH))!!.value.toInt()
            nozomiIdsToMangas(listOf(id)).map { mangas ->
                MangasPage(mangas, false)
            }.toObservable()
        } else {
            if (query.isBlank()) {
                val area = filters.filterIsInstance<TypeFilter>()
                    .joinToString("") {
                        (it as UriPartFilter).toUriPart()
                    }
                val keyword = filters.filterIsInstance<Text>().toString()
                    .replace("[", "").replace("]", "")
                val popular = filters.filterIsInstance<SortFilter>()
                    .joinToString("") {
                        (it as UriPartFilter).toUriPart()
                    } == "true"

                // TODO Cache the results coming out of HitomiNozomi (this TODO dates back to TachiyomiEH)
                val hn = Single.zip(tagIndexVersion(), galleryIndexVersion()) { tv, gv -> tv to gv }
                    .map { HitomiNozomi(client, it.first, it.second) }
                val base = hn.flatMap { n ->
                    n.getGalleryIdsForQuery("$area:${URLEncoder.encode(keyword, "utf-8").replace("+", "%20")}", nozomiLang, popular).map { n to it.toSet() }
                }
                base.flatMap { (_, ids) ->
                    val chunks = ids.chunked(PAGE_SIZE)

                    nozomiIdsToMangas(chunks[page - 1]).map { mangas ->
                        MangasPage(mangas, page < chunks.size)
                    }
                }.toObservable()
            } else {
                val splitQuery = query.toLowerCase(Locale.ENGLISH).split(" ")

                val positive = splitQuery.filter {
                    COMMON_WORDS.any { word ->
                        it !== word
                    } && !it.startsWith('-')
                }.toMutableList()
                if (nozomiLang != "all") positive += "language:$nozomiLang"
                val negative = (splitQuery - positive).map { it.removePrefix("-") }

                // TODO Cache the results coming out of HitomiNozomi (this TODO dates back to TachiyomiEH)
                val hn = Single.zip(tagIndexVersion(), galleryIndexVersion()) { tv, gv -> tv to gv }
                    .map { HitomiNozomi(client, it.first, it.second) }

                var base = if (positive.isEmpty()) {
                    hn.flatMap { n ->
                        n.getGalleryIdsFromNozomi(null, "index", "all", false)
                            .map { n to it.toSet() }
                    }
                } else {
                    val q = positive.removeAt(0)
                    hn.flatMap { n -> n.getGalleryIdsForQuery(q, nozomiLang, false).map { n to it.toSet() } }
                }

                base = positive.fold(base) { acc, q ->
                    acc.flatMap { (nozomi, mangas) ->
                        nozomi.getGalleryIdsForQuery(q, nozomiLang, false).map {
                            nozomi to mangas.intersect(it)
                        }
                    }
                }

                base = negative.fold(base) { acc, q ->
                    acc.flatMap { (nozomi, mangas) ->
                        nozomi.getGalleryIdsForQuery(q, nozomiLang, false).map {
                            nozomi to (mangas - it)
                        }
                    }
                }

                base.flatMap { (_, ids) ->
                    val chunks = ids.chunked(PAGE_SIZE)

                    nozomiIdsToMangas(chunks[page - 1]).map { mangas ->
                        MangasPage(mangas, page < chunks.size)
                    }
                }.toObservable()
            }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException("Not used")
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException("Not used")

    // Filter

    override fun getFilterList() = FilterList(
        Filter.Header(Filter_SEARCH_MESSAGE),
        Filter.Separator(),
        SortFilter(),
        TypeFilter(),
        Text("Keyword")
    )

    private class TypeFilter : UriPartFilter(
        "category",
        Array(FILTER_CATEGORIES.size) { i ->
            val category = FILTER_CATEGORIES[i]
            Pair(category, category)
        }
    )

    private class SortFilter : UriPartFilter(
        "Ordered by",
        arrayOf(
            Pair("Date Added", "false"),
            Pair("Popularity", "true")
        )
    )

    private open class UriPartFilter(
        displayName: String,
        val pair: Array<Pair<String, String>>,
        defaultState: Int = 0
    ) : Filter.Select<String>(displayName, pair.map { it.first }.toTypedArray(), defaultState) {
        open fun toUriPart() = pair[state].second
    }

    private class Text(name: String) : Filter.Text(name) {
        override fun toString(): String {
            return state
        }
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$LTN_BASE_URL/galleries/${hlIdFromUrl(manga.url)}.js")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        getcommon()
        getgg()

        val str = response.body!!.string()
        val jsondata = json.decodeFromString<HitomiGalleryDto>(str.removePrefix("var galleryinfo = "))

        fun String.replaceSpaces() = this.replace(" ", "_")
        val quickjs = QuickJs.create()

        quickjs.evaluate("var document = {'location': {'hostname': 'ltn.hitomi.la'}};")
        quickjs.evaluate(common!!)
        quickjs.evaluate(gg!!)

        val manga = SManga.create().apply {
            title = jsondata.title
            thumbnail_url = quickjs.evaluate("url_from_url_from_hash('${jsondata.id}', ${json.encodeToString(jsondata.files.first())}, 'avifbigtn', 'avif', 'tn')") as String
            author = jsondata.artists?.joinToString { it.artist }
            genre =
                listOfNotNull(
                    jsondata.tags?.joinToString {
                        when {
                            it.url.contains("/female%3A") -> "female:${it.tag.replaceSpaces()}"
                            it.url.contains("/male%3A") -> "male:${it.tag.replaceSpaces()}"
                            else -> it.tag
                        }
                    },
                    jsondata.parodys?.joinToString { "series:${it.parody.replaceSpaces()}" },
                    jsondata.characters?.joinToString { "character:${it.character.replaceSpaces()}" }
                ).joinToString()
            description = jsondata.date
        }

        quickjs.close()
        return manga
    }

    // Chapters

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.just(
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Chapter"
                    chapter_number = 0.0f
                }
            )
        )
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException("Not used")

    // Pages

    private fun hlIdFromUrl(url: String) =
        url.split('/').last().split('-').last().substringBeforeLast('.')

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$LTN_BASE_URL/galleries/${hlIdFromUrl(chapter.url)}.js")
    }

    override fun pageListParse(response: Response): List<Page> {
        getgg()
        val duktape = QuickJs.create()
        duktape.evaluate(gg!!)

        val str = response.body!!.string()
        val json = json.decodeFromString<HitomiGalleryDto>(str.removePrefix("var galleryinfo = "))
        val pages = json.files.mapIndexed { i, jsonElement ->
            // https://ltn.hitomi.la/reader.js
            // function make_image_element()
            val hash = jsonElement.hash
            var ext = jsonElement.name.split('.').last()
            var path = "images"
            var secondSubdomain = "b"
            if (hitomiAlwaysWebp() && jsonElement.haswebp == 1) {
                path = "webp"
                ext = "webp"
                secondSubdomain = "a"
            }
            if (hitomiAlwaysAvif() && jsonElement.hasavif == 1) {
                path = "avif"
                ext = "avif"
                secondSubdomain = "a"
            }

            val b = duktape.evaluate("gg.b;") as String
            val s = duktape.evaluate("gg.s(\"$hash\");") as String
            val m = duktape.evaluate("gg.m($s).toString();") as String

            var firstSubdomain = "a"
            if (m == "1") {
                firstSubdomain = "b"
            }

            Page(i, "", "https://$firstSubdomain$secondSubdomain.hitomi.la/$path/$b$s/$hash.$ext")
        }
        duktape.close()
        return pages
    }

    private fun getgg() {
        if (gg.isNullOrEmpty()) {
            val response = client.newCall(GET("$LTN_BASE_URL/gg.js?_=${System.currentTimeMillis()}")).execute()
            gg = response.body!!.string()
            gg = gg!!.replace("'use strict';", "")
        }
    }

    private fun getcommon() {
        if (common.isNullOrEmpty()) {
            val response = client.newCall(GET("$LTN_BASE_URL/common.js")).execute()
            common = response.body!!.string().substringBefore("function show_loading()")
        }
    }

    override fun imageRequest(page: Page): Request {
        val request = super.imageRequest(page)
        val hlId = request.url.pathSegments.let {
            it[it.lastIndex - 1]
        }
        return request.newBuilder()
            .header("Referer", "$BASE_URL/reader/$hlId.html")
            .build()
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used")

    companion object {
        private const val INDEX_VERSION_CACHE_TIME_MS = 1000 * 60 * 10
        private const val PAGE_SIZE = 25

        const val PREFIX_ID_SEARCH = "id:"
        val NOZOMI_ID_PATTERN = "[0-9]*(?=.html)".toRegex()
        val HEXADECIMAL = "0[xX][0-9a-fA-F]+".toRegex()

        // Common English words and Japanese particles
        private val COMMON_WORDS = listOf(
            "a", "be", "boy", "de", "girl", "ga", "i", "is", "ka", "na",
            "ni", "ne", "no", "suru", "to", "wa", "wo", "yo",
            "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"
        )

        // From HitomiSearchMetaData
        const val LTN_BASE_URL = "https://ltn.hitomi.la"
        const val BASE_URL = "https://hitomi.la"

        // Filter
        private val FILTER_CATEGORIES = listOf(
            "tag", "male", "female", "type",
            "artist", "series", "character", "group"
        )
        private const val Filter_SEARCH_MESSAGE = "NOTE: Ignored if using text search!"

        // Preferences
        private const val WEBP_PREF_KEY = "HITOMI_WEBP"
        private const val WEBP_PREF_TITLE = "Webp pages"
        private const val WEBP_PREF_SUMMARY = "Download webp pages instead of jpeg (when available)"
        private const val WEBP_PREF_DEFAULT_VALUE = true

        private const val AVIF_PREF_KEY = "HITOMI_AVIF"
        private const val AVIF_PREF_TITLE = "Avif pages"
        private const val AVIF_PREF_SUMMARY = "Download avif pages instead of jpeg or webp (when available)"
        private const val AVIF_PREF_DEFAULT_VALUE = true

        private const val COVER_PREF_KEY = "HITOMI_COVERS"
        private const val COVER_PREF_TITLE = "Use HQ covers"
        private const val COVER_PREF_SUMMARY = "See HQ covers while browsing"
        private const val COVER_PREF_DEFAULT_VALUE = true
    }

    // Preferences

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun setupPreferenceScreen(screen: AndroidXPreferenceScreen) {
        val webpPref = AndroidXCheckBoxPreference(screen.context).apply {
            key = "${WEBP_PREF_KEY}_$lang"
            title = WEBP_PREF_TITLE
            summary = WEBP_PREF_SUMMARY
            setDefaultValue(WEBP_PREF_DEFAULT_VALUE)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean("${WEBP_PREF_KEY}_$lang", checkValue).commit()
            }
        }

        val avifPref = AndroidXCheckBoxPreference(screen.context).apply {
            key = "${AVIF_PREF_KEY}_$lang"
            title = AVIF_PREF_TITLE
            summary = AVIF_PREF_SUMMARY
            setDefaultValue(AVIF_PREF_DEFAULT_VALUE)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean("${AVIF_PREF_KEY}_$lang", checkValue).commit()
            }
        }

        val coverPref = AndroidXCheckBoxPreference(screen.context).apply {
            key = "${COVER_PREF_KEY}_$lang"
            title = COVER_PREF_TITLE
            summary = COVER_PREF_SUMMARY
            setDefaultValue(COVER_PREF_DEFAULT_VALUE)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean("${COVER_PREF_KEY}_$lang", checkValue).commit()
            }
        }

        screen.addPreference(webpPref)
        screen.addPreference(avifPref)
        screen.addPreference(coverPref)
    }

    private fun hitomiAlwaysWebp(): Boolean = preferences.getBoolean("${WEBP_PREF_KEY}_$lang", WEBP_PREF_DEFAULT_VALUE)
    private fun hitomiAlwaysAvif(): Boolean = preferences.getBoolean("${AVIF_PREF_KEY}_$lang", AVIF_PREF_DEFAULT_VALUE)
    private fun useHqThumbPref(): Boolean = preferences.getBoolean("${COVER_PREF_KEY}_$lang", COVER_PREF_DEFAULT_VALUE)
}
