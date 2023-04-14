package eu.kanade.tachiyomi.extension.en.realmscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class RealmScans : MangaThemesia("Realm Scans", "https://realmscans.com", "en", "/series") {

    override val client: OkHttpClient = super.client.newBuilder()
        // .rateLimit(1, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)

    override fun pageListParse(document: Document): List<Page> {
        return super.pageListParse(document)
            .distinctBy { it.imageUrl }
            .mapIndexed { i, page -> Page(i, imageUrl = page.imageUrl) }
    }

    override fun popularMangaFromElement(element: Element) =
        replaceRandomUrlPartInManga(super.popularMangaFromElement(element))

    override fun latestUpdatesFromElement(element: Element) =
        replaceRandomUrlPartInManga(super.latestUpdatesFromElement(element))

    override fun searchMangaFromElement(element: Element) =
        replaceRandomUrlPartInManga(super.searchMangaFromElement(element))

    override fun chapterFromElement(element: Element) =
        replaceRandomUrlPartInChapter(super.chapterFromElement(element))

    private fun replaceRandomUrlPartInManga(manga: SManga): SManga {
        val split = manga.url.split("/")
        manga.url = split.slice(split.indexOf("series") until split.size).joinToString("/", "/")
        return manga
    }

    private fun replaceRandomUrlPartInChapter(chapter: SChapter): SChapter {
        val split = chapter.url.split("/")
        if (split.size > 2) {
            chapter.url = split.slice(1 until split.size).joinToString("/", "/")
        }
        return chapter
    }
}
