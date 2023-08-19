package eu.kanade.tachiyomi.multisrc.heancms

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class HeanCmsGenerator : ThemeSourceGenerator {

    override val themePkg = "heancms"

    override val themeClass = "HeanCms"

    override val baseVersionCode: Int = 17

    override val sources = listOf(
        SingleLang("YugenMangas", "https://yugenmangas.net", "es", isNsfw = true, overrideVersionCode = 6),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            HeanCmsGenerator().createAll()
        }
    }
}
