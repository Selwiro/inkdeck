package ink.inkdeck.rslib

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * rslib 运行时通道验收（T003）。
 *
 * 在真实 Android 设备/模拟器上验证：.so 能加载、openBackend 返回可用句柄、
 * runMethodRaw 能完成一次 protobuf 请求-响应、后端错误码能映射成 [RslibException]。
 * 这里刻意不做 mock：测的就是 JNI 边界和 protobuf 字节契约。
 */
@RunWith(AndroidJUnit4::class)
class RslibRuntimeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun loadDescriptors() {
        Proto.load(context)
    }

    private fun cacheDir(name: String): File {
        val dir = File(context.cacheDir, "rslib-test-$name")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    @Test
    fun nativeLibraryLoadsAndReportsDiagnostics() {
        val start = System.nanoTime()
        val available = NativeBridge.isAvailable
        val loadMs = millisSince(start)
        Log.i(TAG, "loadLibrary=${loadMs}ms available=$available")
        assertTrue("librslib_bridge.so 未打包进 APK", available)

        val diagnostics = NativeBridge.diagnostics()
        Log.i(TAG, "diagnostics=$diagnostics")
        assertTrue("诊断信息应包含 rslib-bridge 版本：$diagnostics", diagnostics.contains("rslib-bridge"))
        assertTrue("诊断信息应给出版本：$diagnostics", diagnostics.contains("| anki"))
    }

    @Test
    fun openBackendAndRunDeckTreeRoundTrip() {
        val root = cacheDir("roundtrip")
        AnkiBackend(root).use { backend ->
            val start = System.nanoTime()
            backend.open()
            backend.openCollection()
            val openMs = millisSince(start)
            Log.i(TAG, "openBackend+openCollection=${openMs}ms collection=${backend.collectionFile}")

            assertTrue("openBackend 应返回非零句柄", backend.isOpen)
            assertTrue("集合应已打开", backend.isCollectionOpen)
            assertTrue("打开集合后应生成 collection.anki2", backend.collectionFile.isFile)

            // 单入口 protobuf 往返：请求按 descriptor 动态编码，响应动态解码。
            val request = Proto.builder(DECK_TREE_REQUEST)
                .putNum("now", System.currentTimeMillis() / 1000)
                .build()
            val response = backend.callDecoding(
                service = "DecksService",
                method = "DeckTree",
                request = request,
                responseType = DECK_TREE_NODE,
            )
            val roots = response.subs("children")
            Log.i(
                TAG,
                "deckTree roots=${roots.size} first=${roots.firstOrNull()?.str("name")}",
            )
            assertTrue("牌组树至少应含默认牌组", roots.isNotEmpty())
        }
    }

    @Test
    fun closedBackendRejectsCallsAndDoubleCloseIsSafe() {
        val backend = AnkiBackend(cacheDir("lifecycle"))
        backend.open()
        backend.openCollection()
        backend.close()
        assertFalse(backend.isOpen)

        val error = assertThrows(RslibException::class.java) {
            backend.callBytes("DecksService", "DeckTree", ByteArray(0))
        }
        Log.i(TAG, "closedHandle=${error.message}")
        assertTrue(error.message!!.contains("后端尚未打开"))

        // 重复关闭不应崩：Kotlin 层句柄已归零，直接 no-op。
        backend.close()
        backend.close()
    }

    @Test
    fun unknownMethodIsRejected() {
        AnkiBackend(cacheDir("unknown-method")).use { backend ->
            backend.open()
            val error = assertThrows(RslibException::class.java) {
                backend.callBytes("DecksService", "NoSuchMethod", ByteArray(0))
            }
            Log.i(TAG, "unknownMethod=${error.message}")
            assertTrue(error.message!!.contains("未知的后端方法"))
        }
    }

    @Test
    fun corruptInitBytesAreRejected() {
        val error = assertThrows(RslibException::class.java) {
            NativeBridge.openBackend(byteArrayOf(0x0A, 0x7F, 0x01))
        }
        Log.i(TAG, "badInit=${error.message}")
        assertTrue("初始化失败应给出可读原因", error.message!!.isNotBlank())
    }

    @Test
    fun corruptCollectionFileSurfacesBackendError() {
        val root = cacheDir("corrupt")
        val bogus = File(root, "collection.anki2")
        bogus.writeText("这不是一个 SQLite 数据库")

        AnkiBackend(root).use { backend ->
            backend.open()
            val error = assertThrows(RslibException::class.java) {
                backend.openCollection()
            }
            val message = error.message.orEmpty()
            Log.i(TAG, "corruptCollection kind=${error.kind} message=$message")
            // 后端错误已被解成 BackendError.Kind：类型要能解析出来，文本不能是裸 panic。
            assertTrue("应解析出后端错误类型：${error.kind} / $message", error.kind == RslibException.Kind.IO_ERROR || error.kind == RslibException.Kind.DB_ERROR)
            assertFalse("不应是空消息", message.isBlank())
        }
    }

    private companion object {
        const val TAG = "RslibRuntimeTest"
        const val DECK_TREE_REQUEST = "anki.decks.DeckTreeRequest"
        const val DECK_TREE_NODE = "anki.decks.DeckTreeNode"
    }
}
