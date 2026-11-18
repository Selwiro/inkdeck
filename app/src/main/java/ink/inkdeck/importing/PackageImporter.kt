package ink.inkdeck.importing

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import ink.inkdeck.R
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.data.AppLocale
import ink.inkdeck.data.ImportResult
import ink.inkdeck.rslib.RslibException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** 导入被拒绝的原因。message 直接可以给用户看，不带技术栈信息。 */
sealed class ImportFailure(message: String) : Exception(message) {
    class NotAPackage(message: String) : ImportFailure(message)
    class InsufficientSpace(message: String) : ImportFailure(message)
    class CorruptPackage(message: String) : ImportFailure(message)
    class Cancelled(message: String) : ImportFailure(message)
}

/** 复制到私有目录、已校验过的待导入包。 */
data class StagedPackage(
    val file: File,
    val displayName: String,
    val sha256: String,
    val sizeBytes: Long,
    /** 同一个包之前导入过（按内容哈希判断），导入前要确认。 */
    val alreadyImported: Boolean,
)

/**
 * .apkg 导入：SAF 选择 → 复制到 cache → 校验 → rslib 导入 → 记录内容哈希。
 *
 * 清理语义：
 * - 复制阶段失败或被取消：临时文件立即删除；
 * - 导入阶段失败或取消：上游在 SQLite 事务里回滚，卡片/牌组不留残片；
 *   媒体文件在事务外写入，极端情况下可能留下孤儿文件（cards 不会引用它们），
 *   下次 MediaService.CheckMedia 可清理；
 * - 进程被杀：临时包留在 cache/imports，下次启动按时间清理（见 [cleanupStale]）。
 */
class PackageImporter(
    context: Context,
    private val repository: AnkiRepository,
    private val store: ImportedPackageStore = ImportedPackageStore(context),
) {
    private val appContext = context.applicationContext
    private val importDir = File(appContext.cacheDir, "imports")

    /** 错误文案跟随应用内语言覆盖，而不是固定的系统 Application context。 */
    private val localizedContext get() = AppLocale.wrap(appContext)

    /** 复制并校验一个 SAF 选中的文件。[onProgress] 在 IO 线程回调已复制字节。 */
    suspend fun stage(
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): StagedPackage {
        val displayName = withContext(Dispatchers.IO) { queryDisplayName(uri) } ?: "import.apkg"
        val declaredSize = withContext(Dispatchers.IO) { querySize(uri) }
        val target = withContext(Dispatchers.IO) {
            importDir.mkdirs()
            cleanupStale(importDir)
            if (declaredSize != null) {
                val available = StatFs(importDir.absolutePath).availableBytes
                if (available < declaredSize + SPACE_MARGIN_BYTES) {
                    throw ImportFailure.InsufficientSpace(
                        localizedContext.getString(
                            R.string.import_err_no_space,
                            declaredSize.asMb(),
                            available.asMb(),
                        ),
                    )
                }
            }
            File(importDir, "incoming-${System.currentTimeMillis()}.apkg")
        }
        try {
            withContext(Dispatchers.IO) {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw ImportFailure.CorruptPackage(
                        localizedContext.getString(
                            R.string.import_err_corrupt,
                            localizedContext.getString(R.string.import_err_open_file),
                        ),
                    )
                input.use { source ->
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 100) {
                                lastReport = now
                                onProgress(copied, declaredSize ?: 0L)
                            }
                        }
                        onProgress(copied, declaredSize ?: copied)
                    }
                }
            }
            return register(target, displayName)
        } catch (error: Throwable) {
            target.delete()
            throw when (error) {
                is ImportFailure -> error
                is IOException -> ImportFailure.CorruptPackage(
                    localizedContext.getString(
                        R.string.import_err_copy_failed_detail,
                        error.message ?: localizedContext.getString(R.string.import_err_copy_failed),
                    ),
                )
                else -> error
            }
        }
    }

    /**
     * 把一个已经在本地磁盘上的包登记为待导入（校验 + 内容哈希 + 重复判断）。
     *
     * [import] 成功或失败后都会删除这个文件，调用方不要复用。
     */
    suspend fun register(file: File, displayName: String = file.name): StagedPackage =
        withContext(Dispatchers.IO) {
            importDir.mkdirs()
            validatePackage(file)
            val sha256 = sha256(file)
            StagedPackage(
                file = file,
                displayName = displayName,
                sha256 = sha256,
                sizeBytes = file.length(),
                alreadyImported = store.contains(sha256),
            )
        }

    /**
     * 执行导入。[onStage] 收到后端给的阶段文本（展示为静态文案，不做进度条动画）。
     * 成功返回计数，并记录内容哈希；取消/失败抛 [ImportFailure] 或 [RslibException]。
     */
    suspend fun import(
        staged: StagedPackage,
        onStage: (stage: String) -> Unit = {},
    ): ImportResult = coroutineScope {
        val poller = launch(Dispatchers.Default) {
            while (isActive) {
                repository.progressStage()?.let(onStage)
                delay(PROGRESS_POLL_MILLIS)
            }
        }
        try {
            val result = repository.importPackage(staged.file.absolutePath)
            store.markImported(staged)
            result
        } catch (error: RslibException) {
            if (error.kind == RslibException.Kind.INTERRUPTED) {
                throw ImportFailure.Cancelled(
                    localizedContext.getString(R.string.import_err_cancelled),
                )
            }
            throw error
        } finally {
            poller.cancel()
            staged.file.delete()
            onStage("")
        }
    }

    /** 请求后端在下一个检查点中断导入；事务随之回滚。 */
    suspend fun cancel() = repository.requestAbort()

    /** 用户拒绝重复导入时丢弃临时文件。 */
    fun discard(staged: StagedPackage) {
        staged.file.delete()
    }

    private fun validatePackage(file: File) {
        if (!file.isFile || file.length() == 0L) {
            throw ImportFailure.NotAPackage(
                localizedContext.getString(
                    R.string.import_err_not_package,
                    localizedContext.getString(R.string.import_err_empty_file),
                ),
            )
        }
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        if (!header.contentEquals(ZIP_MAGIC)) {
            throw ImportFailure.NotAPackage(
                localizedContext.getString(
                    R.string.import_err_not_package,
                    localizedContext.getString(R.string.import_err_not_zip),
                ),
            )
        }
        try {
            ZipFile(file).use { zip ->
                val hasCollection = zip.entries().asSequence().any { entry ->
                    COLLECTION_ENTRIES.any { it == entry.name }
                }
                if (!hasCollection) {
                    throw ImportFailure.NotAPackage(
                        localizedContext.getString(
                            R.string.import_err_not_package,
                            localizedContext.getString(R.string.import_err_no_collection),
                        ),
                    )
                }
            }
        } catch (error: ZipException) {
            throw ImportFailure.CorruptPackage(
                localizedContext.getString(
                    R.string.import_err_corrupt,
                    error.message ?: localizedContext.getString(R.string.import_err_zip_broken),
                ),
            )
        }
    }

    /** 清掉上次崩溃/被杀留下的临时包（超过 [STALE_AFTER_MILLIS] 的）。 */
    private fun cleanupStale(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MILLIS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun querySize(uri: Uri): Long? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val PROGRESS_POLL_MILLIS = 400L
        const val STALE_AFTER_MILLIS = 24 * 60 * 60 * 1000L
        /** 复制期间还要写导入结果与日志，留一点余量。 */
        const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val COLLECTION_ENTRIES = listOf("collection.anki21b", "collection.anki21", "collection.anki2")
    }
}

private fun Long.asMb(): String = "%.1f MB".format(this / 1024.0 / 1024.0)
