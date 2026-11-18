package ink.inkdeck.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * 显示设置（内存态 + SharedPreferences 持久化）。
 *
 * 字号有两种来源：用户手动选过的档位优先；没选过时用 [deviceDefaultFontSizeSp]，
 * 由 MainActivity 按窗口宽度档位写入（小屏 18 / 中屏 22 / 大屏与超大屏 26）。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("inkdeck_settings", Context.MODE_PRIVATE)

    /** 用户手动选过的字号；没有选过时为 [NO_CHOICE]。 */
    private var chosenFontSizeSp by mutableIntStateOf(prefs.getInt(KEY_FONT_SIZE, NO_CHOICE))

    /** 屏幕宽度档位对应的默认字号，只在本机没有用户选择时生效。 */
    var deviceDefaultFontSizeSp by mutableIntStateOf(DEFAULT_FONT_SIZE)
        private set

    /** 当前生效字号：用户选择优先，否则跟随屏幕宽度档位的默认值。 */
    val fontSizeSp: Int
        get() = chosenFontSizeSp.takeIf { it in FONT_SIZES } ?: deviceDefaultFontSizeSp

    /** 对比度档位 0=标准（纯黑/纯白）/1=柔和/2=更柔和。 */
    var contrast by mutableIntStateOf(prefs.getInt(KEY_CONTRAST, 0))
        private set

    /** "system" / "en" / "zh"；默认跟随系统。 */
    var languageTag by mutableStateOf(
        prefs.getString(AppLocale.KEY_LANGUAGE, AppLocale.SYSTEM) ?: AppLocale.SYSTEM,
    )
        private set

    /** 是否比本机默认字号更大（用于需要按字号降密度的版式）。 */
    val isLargeFont: Boolean get() = fontSizeSp > deviceDefaultFontSizeSp

    /**
     * 屏幕宽度变化（冷启动、转屏、分屏）时更新默认字号。
     *
     * 只改默认值，不写 SharedPreferences：用户没选过就继续跟随屏幕，
     * 选过则 [fontSizeSp] 仍返回用户选择。
     */
    fun setDeviceDefaultFontSize(sizeSp: Int) {
        deviceDefaultFontSizeSp =
            FONT_SIZES.firstOrNull { it == sizeSp } ?: DEFAULT_FONT_SIZE
    }

    fun setFontSize(sizeSp: Int) {
        val normalized = FONT_SIZES.firstOrNull { it == sizeSp } ?: deviceDefaultFontSizeSp
        chosenFontSizeSp = normalized
        prefs.edit { putInt(KEY_FONT_SIZE, normalized) }
    }

    /** 在 [FONT_SIZES] 里循环切换，返回新档位。 */
    fun cycleFontSize(): Int {
        val index = FONT_SIZES.indexOf(fontSizeSp)
        val next = FONT_SIZES[(index + 1) % FONT_SIZES.size]
        setFontSize(next)
        return next
    }

    fun changeLanguage(tag: String) {
        val normalized = if (tag in AppLocale.TAGS) tag else AppLocale.SYSTEM
        languageTag = normalized
        prefs.edit { putString(AppLocale.KEY_LANGUAGE, normalized) }
    }

    fun updateContrast(level: Int) {
        val normalized = level.coerceIn(0, CONTRAST_LEVELS - 1)
        contrast = normalized
        prefs.edit { putInt(KEY_CONTRAST, normalized) }
    }

    companion object {
        /** 三档字号（验收要求 18/22/26sp）。 */
        val FONT_SIZES = listOf(18, 22, 26)
        const val CONTRAST_LEVELS = 3

        /** 用户未选择时的兜底值；实际默认由屏幕宽度档位决定。 */
        const val DEFAULT_FONT_SIZE = 18
        private const val NO_CHOICE = -1
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_CONTRAST = "contrast"
    }
}
