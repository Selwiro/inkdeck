package ink.inkdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * 整屏刷新控制器（只用标准 Android API + 应用内重绘）。
 *
 * 做法：把 [generation] 加一，页面用 `key(generation)` 包住内容强制整页重建，
 * 再叠一帧纯纸色/纯墨色的全屏遮罩，让底层把整个窗口区域重绘一次。
 * 不调用任何厂商私有接口；真机上是「闪一下」，模拟器上基本看不出来。
 */
class FullRefresher {
    var generation by mutableIntStateOf(0)
        private set

    fun refresh() {
        generation++
    }
}

/** 全屏遮罩：只在刷新请求后的一小段时间内显示，颜色与纸色相反，用来逼出整屏重绘。 */
@Composable
fun FullRefreshFlash(generation: Int, flashColor: Color) {
    if (generation == 0) return
    var visible by remember(generation) { mutableStateOf(true) }
    LaunchedEffect(generation) {
        delay(FLASH_MILLIS)
        visible = false
    }
    if (visible) {
        Box(Modifier.fillMaxSize().background(flashColor))
    }
}

private const val FLASH_MILLIS = 90L
