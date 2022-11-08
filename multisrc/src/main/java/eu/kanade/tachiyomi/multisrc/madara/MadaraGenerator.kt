package eu.kanade.tachiyomi.multisrc.madara

// import generator.ThemeSourceData.MultiLang
import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class MadaraGenerator : ThemeSourceGenerator {

    override val themePkg = "madara"

    override val themeClass = "Madara"

    override val baseVersionCode: Int = 24

    override val sources = listOf(
        SingleLang("DragonTea", "https://dragontea.ink", "en", overrideVersionCode = 3),
        SingleLang("Reset Scans", "https://reset-scans.com", "en", overrideVersionCode = 6),
        SingleLang("Setsu Scans", "https://setsuscans.com", "en", overrideVersionCode = 2),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            MadaraGenerator().createAll()
        }
    }
}
