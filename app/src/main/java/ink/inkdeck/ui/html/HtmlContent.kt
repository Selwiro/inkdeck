package ink.inkdeck.ui.html

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.inkdeck.data.AnkiRepository
import ink.inkdeck.ui.InkText
import ink.inkdeck.ui.LocalInkBodySize
import ink.inkdeck.ui.LocalInkColors
import ink.inkdeck.ui.MediaImage
import ink.inkdeck.ui.SoundButton

/**
 * 把 [MiniHtml] 解出来的块画到 Compose 上。
 *
 * 墨水屏约束：没有动画、没有渐变；颜色只用来保证对比度（浅色文字压到墨色上），
 * 边框/背景按 CSS 画成纯色块。正文区允许纵向滚动（单层，不与列表嵌套）。
 */
@Composable
fun HtmlContent(
    html: String,
    css: String,
    repository: AnkiRepository,
    bodySizeSp: Float,
    scrollable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(html, css, bodySizeSp) {
        MiniHtml.render(
            html = html,
            css = css,
            baseSizeSp = bodySizeSp,
            // 模板 CSS 大量使用 px；按用户字号相对 18sp 的比例整体放大，否则字号设置对正文无效。
            scale = bodySizeSp / 18f,
        )
    }
    val ink = LocalInkColors.current.ink

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        blocks.forEach { block ->
            when (block) {
                is RenderBlock.Paragraph -> ParagraphBlock(block, ink)
                is RenderBlock.Group -> GroupBlock(block, repository, ink)
                is RenderBlock.Sound -> SoundButton(repository, block.name)
                is RenderBlock.Image -> MediaImage(repository, block.name)
                RenderBlock.Divider -> DividerBlock(ink)
            }
        }
    }
}

@Composable
private fun GroupBlock(
    group: RenderBlock.Group,
    repository: AnkiRepository,
    ink: Color,
) {
    val style = group.style
    if (style.dividerTop) DividerBlock(ink)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (style.borderColor != null && style.borderWidth > 0f) {
                    Modifier.border(
                        width = maxOf(style.borderWidth, 1f).dp,
                        color = resolveColor(style.borderColor, ink),
                    )
                } else {
                    Modifier
                },
            )
            .padding(style.padding.dp),
    ) {
        Column {
            group.blocks.forEach { child ->
                when (child) {
                    is RenderBlock.Paragraph -> ParagraphBlock(child, ink)
                    is RenderBlock.Image -> MediaImage(repository, child.name)
                    is RenderBlock.Group -> GroupBlock(child, repository, ink)
                    is RenderBlock.Sound -> SoundButton(repository, child.name)
                    RenderBlock.Divider -> DividerBlock(ink)
                }
            }
        }
    }
    Spacer(Modifier.height(style.marginBottom.dp))
}

/** `<hr>` / 模板伪元素分隔线：细、浅，避免在墨水屏上压过正文。 */
@Composable
private fun DividerBlock(ink: Color) {
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ink.copy(alpha = 0.55f)),
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ParagraphBlock(
    block: RenderBlock.Paragraph,
    ink: Color,
    fillWidth: Boolean = true,
) {
    val style = block.style
    val align = when (style.align) {
        HtmlAlign.START -> androidx.compose.ui.text.style.TextAlign.Start
        HtmlAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
        HtmlAlign.END -> androidx.compose.ui.text.style.TextAlign.End
    }

    Spacer(Modifier.height(style.marginTop.dp))
    if (style.dividerTop) DividerBlock(ink)
    // 卡片一律不画底色（墨水屏上色块只会变成灰带）；只保留边框与分隔线。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (style.borderColor != null && style.borderWidth > 0f) {
                    Modifier.border(
                        width = maxOf(style.borderWidth, 1f).dp,
                        color = resolveColor(style.borderColor, ink),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = style.padding.dp, vertical = style.padding.dp)
            .padding(start = block.indentDp.dp),
    ) {
        Row {
            if (block.bullet != null) {
                InkText(text = block.bullet, size = 18.sp)
                Spacer(Modifier.width(8.dp))
            }
            val bodySize = LocalInkBodySize.current.value
            val baseSize = block.spans.maxOfOrNull { it.style.sizeSp ?: bodySize } ?: bodySize
            val annotated = buildAnnotated(
                spans = block.spans,
                ink = ink,
                defaultSizeSp = baseSize,
            )
            BasicText(
                text = annotated,
                modifier = if (!fillWidth) Modifier else Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = ink,
                    fontSize = baseSize.sp,
                    lineHeight = (baseSize * (style.lineHeight ?: 1.35f)).sp,
                    textAlign = align,
                ),
            )
        }
    }
    if (style.dividerBottom) DividerBlock(ink)
    Spacer(Modifier.height(style.marginBottom.dp))
}

@Composable
private fun buildAnnotated(
    spans: List<TextSpan>,
    ink: Color,
    defaultSizeSp: Float,
): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            val s = span.style
            withStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = when {
                        s.underline && s.strike -> TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                        )
                        s.underline -> TextDecoration.Underline
                        s.strike -> TextDecoration.LineThrough
                        else -> null
                    },
                    fontSize = (s.sizeSp ?: defaultSizeSp).sp,
                    color = s.color?.let { resolveColor(it, ink) } ?: ink,
                ),
            ) {
                append(span.text)
            }
        }
    }

/** 把 CSS 颜色转成 Compose 颜色；白底上的浅色文字压成墨色，避免"看不见的白字"。 */
private fun resolveColor(raw: String?, ink: Color): Color {
    val parsed = raw?.let(::parseColor) ?: return ink
    val luminance = 0.299f * parsed.red + 0.587f * parsed.green + 0.114f * parsed.blue
    return if (luminance > 0.75f) ink else parsed
}

private fun parseColor(raw: String): Color? = runCatching {
    val normalized = when {
        raw.startsWith("#") -> raw
        raw.equals("black", true) -> "#000000"
        raw.equals("white", true) -> "#FFFFFF"
        raw.equals("red", true) -> "#FF0000"
        raw.equals("blue", true) -> "#0000FF"
        raw.equals("green", true) -> "#008000"
        raw.equals("gray", true) || raw.equals("grey", true) -> "#808080"
        else -> return null
    }
    Color(android.graphics.Color.parseColor(normalized))
}.getOrNull()
