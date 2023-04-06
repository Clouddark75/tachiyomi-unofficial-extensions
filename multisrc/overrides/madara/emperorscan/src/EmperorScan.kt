package eu.kanade.tachiyomi.extension.es.emperorscan

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class EmperorScan : Madara(
    "Emperor Scan",
    "https://emperorscan.com",
    "es",
    SimpleDateFormat("MMMM dd, yyyy", Locale("es")),
) {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val hasUaIntercept by lazy {
        client.interceptors.toString().contains("uaIntercept")
    }

    private var userAgent: String? = null

    override val mangaDetailsSelectorDescription = "div.sinopsis div.contenedor"

    override fun pageListParse(document: Document): List<Page> {
        countViews(document)

        return document.select(pageListParseSelector).mapIndexed { index, element ->
            var imageUrl = element.select("img").first()?.let {
                it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src")
            }
            if (getJetPackCDNPref()) {
                imageUrl = imageUrl!!.replace(JETPACK_CDN_REGEX, "")
            }
            Page(
                index,
                document.location(),
                imageUrl,
            )
        }
    }

    private fun getJetPackCDNPref(): Boolean = preferences.getBoolean(PREF_KEY_JETPACK_CDN, PREF_DEFAULT_VALUE_JETPACK_CDN)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        if (hasUaIntercept) {
            val prefUserAgent = SwitchPreferenceCompat(screen.context).apply {
                key = PREF_KEY_RANDOM_UA
                title = TITLE_RANDOM_UA
                summary = if (preferences.getBoolean(PREF_KEY_RANDOM_UA, useRandomUserAgentByDefault)) userAgent else ""
                setDefaultValue(useRandomUserAgentByDefault)

                setOnPreferenceChangeListener { _, newValue ->
                    val useRandomUa = newValue as Boolean
                    preferences.edit().putBoolean(PREF_KEY_RANDOM_UA, useRandomUa).apply()
                    if (!useRandomUa) {
                        Toast.makeText(screen.context, RESTART_APP_STRING, Toast.LENGTH_LONG).show()
                    } else {
                        userAgent = null
                        if (preferences.getString(PREF_KEY_CUSTOM_UA, "").isNullOrBlank().not()) {
                            Toast.makeText(screen.context, SUMMARY_CLEANING_CUSTOM_UA, Toast.LENGTH_LONG).show()
                        }
                    }

                    preferences.edit().putString(PREF_KEY_CUSTOM_UA, "").apply()
                    // prefCustomUserAgent.summary = ""
                    true
                }
            }
            screen.addPreference(prefUserAgent)

            val prefCustomUserAgent = EditTextPreference(screen.context).apply {
                key = PREF_KEY_CUSTOM_UA
                title = TITLE_CUSTOM_UA
                summary = preferences.getString(PREF_KEY_CUSTOM_UA, "")!!.trim()
                setOnPreferenceChangeListener { _, newValue ->
                    val customUa = newValue as String
                    preferences.edit().putString(PREF_KEY_CUSTOM_UA, customUa).apply()
                    if (customUa.isBlank()) {
                        Toast.makeText(screen.context, RESTART_APP_STRING, Toast.LENGTH_LONG).show()
                    } else {
                        userAgent = null
                    }
                    summary = customUa.trim()
                    prefUserAgent.summary = ""
                    prefUserAgent.isChecked = false
                    true
                }
            }
            screen.addPreference(prefCustomUserAgent)
        } else {
            Toast.makeText(screen.context, DOESNOT_SUPPORT_STRING, Toast.LENGTH_LONG).show()
        }
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
