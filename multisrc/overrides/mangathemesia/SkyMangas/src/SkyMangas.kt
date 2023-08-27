package eu.kanade.tachiyomi.extension.es.skymangas

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import java.lang.IllegalArgumentException
import java.text.SimpleDateFormat
import java.util.Locale
import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get




class SkyMangas : MangaThemesia(
    "SkyMangas",
    "https://skymangas.com",
    "es",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("es")),
) {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    override fun pageListParse(document: Document): List<Page> {
        val script = document.selectFirst("div.readercontent > div.wrapper > script")
            ?: return super.pageListParse(document)

        val scriptSrc = script.attr("src")

        if (scriptSrc.startsWith("data:text/javascript;base64,")) {
            val encodedData = scriptSrc.substringAfter("data:text/javascript;base64,")
            val decodedData = Base64.decode(encodedData, Base64.DEFAULT).toString(Charsets.UTF_8)

            val imageListJson = JSON_IMAGE_LIST_REGEX.find(decodedData)?.destructured?.toList()?.get(0).orEmpty()
            val imageList = try {
                json.parseToJsonElement(imageListJson).jsonArray
            } catch (_: IllegalArgumentException) {
                emptyList()
            val scriptPages = imageList.mapIndexed { i, jsonEl ->
            var imageUrl = jsonEl.jsonPrimitive.content
            if (getJetPackCDNPref()) {
                imageUrl = imageUrl.replace(JETPACK_CDN_REGEX, "")             
            }

                        Page(i, "", imageUrl)
        }

        return scriptPages
    }
     private fun getJetPackCDNPref(): Boolean = preferences.getBoolean(PREF_KEY_JETPACK_CDN, PREF_DEFAULT_VALUE_JETPACK_CDN)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        disableJetPackCDNPreferences(screen)
    }

    private fun disableJetPackCDNPreferences(screen: PreferenceScreen) {
        val prefJetPackCDN = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_KEY_JETPACK_CDN
            title = PREF_TITLE_JETPACK_CDN
            summary = PREF_SUMMARY_JETPACK_CDN
            setDefaultValue(PREF_DEFAULT_VALUE_JETPACK_CDN)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean(PREF_KEY_JETPACK_CDN, checkValue).commit()
            }
        }

        screen.addPreference(prefJetPackCDN)
    }

    companion object {
        val JETPACK_CDN_REGEX = "i[0-3].wp.com/".toRegex()

        const val PREF_TITLE_JETPACK_CDN = "Disable JetPack CDN"
        const val PREF_KEY_JETPACK_CDN = "pref_key_jetpack_cdn"
        const val PREF_SUMMARY_JETPACK_CDN = "Puede activar esta opción si las imágenes no cargan correctamente"
        const val PREF_DEFAULT_VALUE_JETPACK_CDN = false
    }
}