package eu.kanade.tachiyomi.extension.en.resetscans

import eu.kanade.tachiyomi.multisrc.madara.Madara

class ResetScans : Madara("Reset Scans", "https://reset-scans.com", "en"/*, java.text.SimpleDateFormat("dd MMM", Locale.US)*/) {
    override val useNewChapterEndpoint = true

//    override fun chapterFromElement(element: Element): SChapter {
//        val chapter = super.chapterFromElement(element)
//
//        with(element) {
//            chapter.date_upload = select("span.chapter-release-date i").firstOrNull()?.text().let { parseChapterDate(it) }
//        }
//
//        return chapter
//    }
}
