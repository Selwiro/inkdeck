package ink.inkdeck.rslib

import com.google.protobuf.DynamicMessage
import com.google.protobuf.Message
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * 后端句柄与集合生命周期。
 *
 * 只有 [call] 会碰原生指针；调用方保证串行（仓库层用单线程调度）。
 * 集合路径约定：`<dir>/collection.anki2`、`<dir>/collection.media/`、`<dir>/collection.media.db2`。
 */
class AnkiBackend(
    private val rootDir: File,
    /** 应用私有临时目录；Android 上必须替代 Rust 默认的 /data/local/tmp。 */
    private val tempDir: File? = null,
) : Closeable {

    @Volatile
    private var handle: Long = 0L
    private var collectionOpen = false

    val isOpen: Boolean get() = handle != 0L
    val isCollectionOpen: Boolean get() = collectionOpen

    val collectionFile: File get() = File(rootDir, "collection.anki2")
    /** 集合连同媒体文件所在目录，卡片 HTML 里的相对图片路径以此为基准。 */
    val collectionDir: File get() = rootDir
    val mediaDir: File get() = File(rootDir, "collection.media")
    private val mediaDb: File get() = File(rootDir, "collection.media.db2")

    fun ensureDirs() {
        rootDir.mkdirs()
        mediaDir.mkdirs()
    }

    /** 打开后端。幂等。 */
    fun open() {
        if (handle != 0L) return
        ensureDirs()
        // 必须在 openBackend 前设置；调用发生在仓库的后端线程，不在主线程加载 .so。
        tempDir?.let {
            it.mkdirs()
            NativeBridge.setTempDir(it.absolutePath)
        }
        val init = Proto.builder("anki.backend.BackendInit")
        val langs = Proto.descriptor("anki.backend.BackendInit").findFieldByName("preferred_langs")!!
        // 跟随系统语言；后端没有对应翻译时回落到英文。
        val locale = Locale.getDefault()
        listOf(locale.toLanguageTag(), locale.language, "en")
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { init.addRepeatedField(langs, it) }
        handle = NativeBridge.openBackend(Proto.encode(init.build()))
    }

    fun openCollection() {
        open()
        if (collectionOpen) return
        ensureDirs()
        val request = Proto.builder("anki.collection.OpenCollectionRequest")
            .putStr("collection_path", collectionFile.absolutePath)
            .putStr("media_folder_path", mediaDir.absolutePath)
            .putStr("media_db_path", mediaDb.absolutePath)
            .build()
        call("CollectionService", "OpenCollection", request)
        collectionOpen = true
    }

    fun closeCollection() {
        if (!collectionOpen) return
        collectionOpen = false
        runCatching { callEmpty("CollectionService", "CloseCollection") }
    }

    /** 原始调用：返回响应字节。失败时抛 [RslibException]。 */
    fun callBytes(service: String, method: String, args: ByteArray): ByteArray {
        if (handle == 0L) throw RslibException("后端尚未打开")
        synchronized(this) {
            return NativeBridge.runMethod(handle, service, method, args)
        }
    }

    /**
     * 导入等长操作进行中的并发调用。
     *
     * 只给 `CollectionService.LatestProgress` / `SetWantsAbort` 这类「只碰后端 progress
     * 状态、不拿集合锁」的方法用：它们不能走 [callBytes] 的 `synchronized`，
     * 否则会一直等到导入结束，进度轮询和取消都成了摆设。
     */
    fun callConcurrent(service: String, method: String, args: ByteArray = ByteArray(0)): ByteArray {
        val current = handle
        if (current == 0L) throw RslibException("后端尚未打开")
        return NativeBridge.runMethod(current, service, method, args)
    }

    fun call(service: String, method: String, request: Message): ByteArray =
        callBytes(service, method, Proto.encode(request))

    /** 解码响应；[responseType] 用 protobuf 全名。 */
    fun callDecoding(
        service: String,
        method: String,
        request: Message,
        responseType: String,
    ): DynamicMessage = Proto.decode(responseType, call(service, method, request))

    /** 请求体为空（proto 的 Empty），直接发 0 字节。 */
    fun callEmpty(service: String, method: String): ByteArray = callBytes(service, method, ByteArray(0))

    override fun close() {
        closeCollection()
        if (handle != 0L) {
            NativeBridge.closeBackend(handle)
            handle = 0L
        }
    }
}
