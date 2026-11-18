package ink.inkdeck.importing

import android.content.Context
import androidx.core.content.edit

/**
 * 记住导入过的包（按文件内容 SHA-256），用于重复导入前确认。
 *
 * 只存哈希与显示名，不存路径——SAF 的 uri 会失效，路径也不是稳定标识。
 */
class ImportedPackageStore(
    context: Context,
    preferencesName: String = "imported_packages",
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun contains(sha256: String): Boolean = prefs.contains(sha256)

    /** 上一次导入这个包的时间（毫秒），没有记录时返回 null。 */
    fun importedAt(sha256: String): Long? = prefs.getString(sha256, null)
        ?.substringAfterLast('|')
        ?.toLongOrNull()

    fun markImported(staged: StagedPackage) {
        prefs.edit {
            putString(staged.sha256, "${staged.displayName}|${System.currentTimeMillis()}")
        }
    }

    fun clear() = prefs.edit { clear() }
}
