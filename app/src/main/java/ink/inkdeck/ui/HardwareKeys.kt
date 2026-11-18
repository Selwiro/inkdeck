package ink.inkdeck.ui

import android.view.KeyEvent

/**
 * 硬件键路由：单 Activity 下把音量键交给当前页面处理（翻页 / 翻面 / 评分）。
 *
 * 用全局回调而不是 Compose 焦点树，是因为墨水屏阅读场景更适合
 * 「哪个页面在屏幕上，键就归谁」，不依赖焦点移动。
 */
object HardwareKeys {

    @Volatile
    var onVolumeDown: (() -> Boolean)? = null

    @Volatile
    var onVolumeUp: (() -> Boolean)? = null

    fun hasHandler(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> onVolumeDown != null
        KeyEvent.KEYCODE_VOLUME_UP -> onVolumeUp != null
        else -> false
    }

    fun handle(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> onVolumeDown?.invoke() == true
        KeyEvent.KEYCODE_VOLUME_UP -> onVolumeUp?.invoke() == true
        else -> false
    }
}
