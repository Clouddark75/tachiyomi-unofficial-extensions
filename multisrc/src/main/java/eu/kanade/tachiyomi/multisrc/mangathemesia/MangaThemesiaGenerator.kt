package eu.kanade.tachiyomi.multisrc.mangathemesia

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

// Formerly WPMangaStream & WPMangaReader -> MangaThemesia
class MangaThemesiaGenerator : ThemeSourceGenerator {

    override val themePkg = "mangathemesia"

    override val themeClass = "MangaThemesia"

    override val baseVersionCode: Int = 25

    override val sources = listOf(
        SingleLang("Luminous Scans", "https://www.luminousscans.com", "en", overrideVersionCode = 1),
        SingleLang("Tecno Scan", "https://tecnoscann.com", "es", overrideVersionCode = 1),
        SingleLang("SkyMangas", "https://skymangas.com", "es", overrideVersionCode = 1),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            MangaThemesiaGenerator().createAll()
        }
    }
}
