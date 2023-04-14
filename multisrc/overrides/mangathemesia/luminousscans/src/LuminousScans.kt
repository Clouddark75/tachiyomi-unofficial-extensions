package eu.kanade.tachiyomi.extension.en.luminousscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element

class LuminousScans : MangaThemesia("Luminous Scans", "https://www.luminousscans.com", "en", mangaUrlDirectory = "/series") {
    override fun searchMangaFromElement(element: Element): SManga {
        val manga = super.searchMangaFromElement(element)

        val response = client.newCall(GET(baseUrl + manga.url, headers)).execute().body.string()

        val postid = response
            .substringAfter("post_id:")
            .substringBefore("}")

        manga.url = "$mangaUrlDirectory?p=$postid"

        return manga
    }

//    override fun chapterFromElement(element: Element): SChapter {
//        val chapter = super.chapterFromElement(element)
//
//        val response = client.newCall(GET(baseUrl + chapter.url, headers)).execute().body.string()
//
//        val postid = response
//            .substringAfter("var chapter_id = ")
//            .substringBefore(";")
//
//        chapter.url = "/?p=$postid"
//
//        return chapter
//    }
}
