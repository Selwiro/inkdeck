package ink.inkdeck

import android.app.Application
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.data.ReviewSessionStore
import ink.inkdeck.data.SettingsStore
import ink.inkdeck.importing.PackageImporter

/**
 * 进程级单例：一个后端句柄、一个导入器。
 *
 * 后端（rslib）本身是全局状态（一个集合只能开一次），放在 Application 里比
 * 放在 Activity/ViewModel 里安全：转屏、返回都不会重复打开集合。
 */
class InkdeckApp : Application() {

    val repository: AnkiRepository by lazy { AnkiRepository(this) }

    val importer: PackageImporter by lazy { PackageImporter(this, repository) }

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val reviewSession: ReviewSessionStore by lazy { ReviewSessionStore(this) }
}
