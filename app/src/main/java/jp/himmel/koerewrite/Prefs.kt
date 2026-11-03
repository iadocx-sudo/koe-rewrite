package jp.himmel.koerewrite

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("koe", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = (sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    var autoReturn: Boolean
        get() = sp.getBoolean("autoReturn", true)
        set(v) = sp.edit().putBoolean("autoReturn", v).apply()

    var replaceField: Boolean
        get() = sp.getBoolean("replaceField", true)
        set(v) = sp.edit().putBoolean("replaceField", v).apply()

    var useScreen: Boolean
        get() = sp.getBoolean("useScreen", true)
        set(v) = sp.edit().putBoolean("useScreen", v).apply()

    var extraRule: String
        get() = sp.getString("extraRule", "") ?: ""
        set(v) = sp.edit().putString("extraRule", v).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        val FALLBACK_MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest")
    }
}
