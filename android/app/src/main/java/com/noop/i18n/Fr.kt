package com.noop.i18n

import android.content.Context
import com.noop.R
import org.json.JSONObject
import java.util.Locale

/**
 * Lightweight runtime French localisation for the Android UI.
 *
 * The Android Compose screens carry their copy as hardcoded English string literals — they never
 * went through `strings.xml`. Rather than a mass extraction, [tr] wraps a literal at its call site
 * and swaps in a French rendering when the device language is French. The translations are the same
 * English→French pairs the iOS String Catalog already ships (bundled as `res/raw/fr_strings.json`).
 *
 * Anything not in the map — an unknown phrase, or a string that carries `$`/`%` format pieces —
 * falls straight through to the English original, so a call to [tr] is always safe and never throws.
 */
object Fr {

    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile private var active: Boolean = false

    /** Parse the bundled dictionary once. Call from `Application.onCreate`. No-op when not French. */
    fun load(context: Context) {
        active = Locale.getDefault().language.lowercase() == "fr"
        if (!active) return
        map = runCatching {
            val text = context.resources.openRawResource(R.raw.fr_strings)
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            val out = HashMap<String, String>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getString(k)
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** English literal in, French out when available and the device language is French. */
    fun tr(en: String): String = if (active) map[en] ?: en else en
}
