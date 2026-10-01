package com.svifi.vitalyspeak

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Health.installCrashLogger(this)
        Prefs.get(this)                         // runs the one-time settings migration
        GroqUsage.init(Prefs.get(this))
        // Re-apply a chosen interface language (AppCompat stores it, this keeps both in sync).
        val tag = Prefs.get(this).getString(Prefs.UI_LANG, "") ?: ""
        if (tag.isNotEmpty() && AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}

/** Interface language: in-app choice, applied to activities and the background service alike. */
object UiLocale {
    fun set(ctx: Context, tag: String) {
        Prefs.get(ctx).edit().putString(Prefs.UI_LANG, tag).apply()
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
    }

    fun current(ctx: Context): String = Prefs.get(ctx).getString(Prefs.UI_LANG, "") ?: ""

    /**
     * Context in the chosen language, for the accessibility service (services don't get
     * AppCompat's per-app language on older Android versions).
     */
    fun wrap(base: Context): Context {
        val tag = base.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getString(Prefs.UI_LANG, "") ?: ""
        if (tag.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(cfg)
    }
}
