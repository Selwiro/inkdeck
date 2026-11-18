package ink.inkdeck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.inkdeck.R
import ink.inkdeck.data.AnkiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 卡片里的媒体：图片直接解码 collection.media 里的文件，音频用 MediaPlayer 播放。
 * 缺文件时显示「（缺图/缺音频：名字）」，不静默留白。
 */
@Composable
internal fun MediaImage(repository: AnkiRepository, name: String) {
    val file = remember(name) { repository.mediaFile(name) }
    if (file == null) {
        InkText(text = stringResource(R.string.media_missing_image, name), size = 16.sp)
        return
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { decodeSampled(file) }
    }
    val image = bitmap
    Spacer(Modifier.height(8.dp))
    if (image == null) {
        InkText(text = stringResource(R.string.media_undecodable_image, name), size = 16.sp)
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = name,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 按最长边采样解码。卡片里经常有手机拍的几千万像素照片，原尺寸解码在低内存
 * 墨水屏设备上很容易 OOM；这里先读尺寸，再按 2 的幂降采样。
 */
private fun decodeSampled(file: File, maxDimension: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    var longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

@Composable
internal fun SoundButton(repository: AnkiRepository, name: String) {
    val context = LocalContext.current
    val file = remember(name) { repository.mediaFile(name) }
    if (file == null) {
        InkText(text = stringResource(R.string.media_missing_audio, name), size = 16.sp)
        return
    }
    val ink = LocalInkColors.current.ink
    val interaction = remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    DisposableEffect(Unit) {
        onDispose { SoundPlayer.stop() }
    }
    Spacer(Modifier.height(4.dp))
    // 和 AnkiDroid 一样只给一个播放按钮：48dp 方块 + ▶，不写文件名。
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(width = 2.dp, color = ink)
            .clickable(interactionSource = interaction, indication = null) {
                SoundPlayer.play(context, file)
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            text = "▶",
            style = androidx.compose.ui.text.TextStyle(color = ink, fontSize = 18.sp),
        )
    }
}

/**
 * 全局只保留一个播放器：卡片里常有多段 [sound:]，连续点两个时不能叠在一起响。
 * 同时申请短暂的音频焦点，播放结束/被替换时释放。
 */
internal object SoundPlayer {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    fun play(context: Context, file: File) {
        stop()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val manager = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        manager?.requestAudioFocus(focus)
        audioManager = manager
        focusRequest = focus
        try {
            val next = MediaPlayer()
            next.setAudioAttributes(attributes)
            next.setDataSource(file.absolutePath)
            next.prepare()
            // 不要在 MediaPlayer 自己的回调里立刻 release，先回主线程再收尾。
            next.setOnCompletionListener {
                Handler(Looper.getMainLooper()).post { stop() }
            }
            next.setOnErrorListener { _, _, _ ->
                Handler(Looper.getMainLooper()).post { stop() }
                true
            }
            next.start()
            player = next
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.release() }
        }
        player = null
        focusRequest?.let { request -> runCatching { audioManager?.abandonAudioFocusRequest(request) } }
        focusRequest = null
        audioManager = null
    }
}
