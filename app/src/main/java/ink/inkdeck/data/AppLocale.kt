package ink.inkdeck.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * 应用内语言覆盖。
 *
 * 默认 [SYSTEM]：跟随系统；用户可在设置页固定英文/简体中文。
 * 用 `attachBaseContext` 包一层 Configuration，UI 资源与 Compose `stringResource`
 * 都会走覆盖后的 locale；`Locale.setDefault` 同时让后端 preferred_langs 跟随。
 */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val CHINESE = "zh"
    const val TRADITIONAL_CHINESE = "zh-Hant"
    val TAGS = listOf(SYSTEM, ENGLISH, CHINESE, TRADITIONAL_CHINESE)

    /** 与 SettingsStore 共用同一个 prefs/key。 */
    const val PREFS = "inkdeck_settings"
    const val KEY_LANGUAGE = "language_tag"

    fun readTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)
            ?: SYSTEM

    fun localeFor(tag: String): Locale = when (tag) {
        ENGLISH -> Locale.ENGLISH
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        TRADITIONAL_CHINESE -> Locale.forLanguageTag(TRADITIONAL_CHINESE)
        else -> systemLocale()
    }

    fun contextLocale(context: Context): Locale = localeFor(readTag(context))

    fun wrap(base: Context): Context {
        val locale = localeFor(readTag(base))
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun systemLocale(): Locale {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }
}
