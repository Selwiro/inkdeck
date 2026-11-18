package ink.inkdeck.rslib

/**
 * rslib（Anki 官方 Rust 后端）的 JNI 入口。
 *
 * 只有三个动作：打开、调用、关闭。调用按「服务名 + 方法名」分发，
 * 索引由 Rust 侧用上游自己的索引表算，Kotlin 不感知数字索引，升级 tag 不用改这里。
 *
 * 加载策略：`System.loadLibrary` 只做一次（[loadResult]），所有入口都先过 [ensureLoaded]。
 * 不把加载隐含在某个属性访问里——否则调用顺序一换就会在首次 native 调用上炸
 * `UnsatisfiedLinkError`，而错误信息还误导成「没实现」。
 *
 * .so 不进版本库，由 rslib/build-android.sh 生成到 app/src/main/jniLibs/。
 */
object NativeBridge {

    private val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("rslib_bridge") }
    }

    /** 原生库是否可用。没跑过构建脚本时为 false，UI 应据此降级而不是崩溃。 */
    val isAvailable: Boolean get() = loadResult.isSuccess

    /** 版本与可分发服务数量，用于启动日志和排障。 */
    fun diagnostics(): String =
        if (isAvailable) {
            externalDiagnostics()
        } else {
            "rslib 原生库未打包（先执行 rslib/build-android.sh）：${loadResult.exceptionOrNull()?.message.orEmpty()}"
        }

    fun openBackend(init: ByteArray): Long {
        ensureLoaded()
        return externalOpenBackend(init).also { if (it == 0L) throw RslibException("后端打开失败") }
    }

    fun runMethod(handle: Long, service: String, method: String, args: ByteArray): ByteArray {
        ensureLoaded()
        return externalRunMethod(handle, service, method, args)
            ?: throw RslibException("$service.$method 没有返回数据")
    }

    /** 把 Rust 的临时目录指到应用 cache；必须在 openBackend 之前调用。 */
    fun setTempDir(path: String) {
        ensureLoaded()
        externalSetTempDir(path)
    }

    fun closeBackend(handle: Long) {
        if (handle == 0L || !isAvailable) return
        externalCloseBackend(handle)
    }

    private fun ensureLoaded() {
        if (isAvailable) return
        throw RslibException(
            "librslib_bridge.so 无法加载：${loadResult.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }.orEmpty()}"
        )
    }

    private external fun externalOpenBackend(init: ByteArray): Long

    private external fun externalRunMethod(
        handle: Long,
        service: String,
        method: String,
        args: ByteArray,
    ): ByteArray?

    private external fun externalSetTempDir(path: String)

    private external fun externalCloseBackend(handle: Long)

    private external fun externalDiagnostics(): String
}
