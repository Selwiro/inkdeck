package ink.inkdeck.ui.html

/**
 * 极简 HTML 树 + CSS 子集渲染。
 *
 * 卡片正文来自 Anki 模板，是「HTML + 一小撮 CSS」。WebView 太重（内存、动画、滚动都不受控），
 * 这里自己解析：块级结构、行内样式、常见 CSS 属性，够在墨水屏上舒服地读。
 *
 * 明确不支持：浮动/栅格/flex、绝对定位、百分比宽度、渐变/阴影/变换、伪元素、@media。
 */
sealed interface HtmlNode {
    data class Element(
        val tag: String,
        val attributes: Map<String, String>,
        val children: MutableList<HtmlNode> = mutableListOf(),
    ) : HtmlNode

    data class Text(val text: String) : HtmlNode
}

data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizeSp: Float? = null,
    val color: String? = null,
)

data class BlockStyle(
    val borderColor: String? = null,
    val borderWidth: Float = 0f,
    val padding: Float = 0f,
    val marginTop: Float = 0f,
    val marginBottom: Float = 0f,
    val align: HtmlAlign = HtmlAlign.START,
    val listStyle: String? = null,
    /** CSS line-height 倍数；null 用默认值。 */
    val lineHeight: Float? = null,
    /** 来自 `::before` / `border-top` 的上分隔线。 */
    val dividerTop: Boolean = false,
    /** 来自 `::after` / `border-bottom` 的下分隔线。 */
    val dividerBottom: Boolean = false,
)

enum class HtmlAlign { START, CENTER, END }

fun BlockStyle.hasDecoration(): Boolean =
    (borderColor != null && borderWidth > 0f) || padding > 0f || dividerTop || dividerBottom

/** 组内段落不再重复父级边框/背景/分隔线（由 Group 统一画），只保留对齐与行高。 */
fun BlockStyle.withoutDecoration(): BlockStyle = copy(
    borderColor = null,
    borderWidth = 0f,
    padding = 0f,
    dividerTop = false,
    dividerBottom = false,
)

data class TextSpan(val text: String, val style: InlineStyle)

sealed interface RenderBlock {
    data class Paragraph(
        val spans: List<TextSpan>,
        val style: BlockStyle,
        val indentDp: Float = 0f,
        val bullet: String? = null,
    ) : RenderBlock

    data class Image(val name: String, val align: HtmlAlign) : RenderBlock

    /** 带边框/背景/内边距的块容器（例如 ul.examples 这类样式）。 */
    data class Group(val style: BlockStyle, val blocks: List<RenderBlock>) : RenderBlock

    /** 内联音频：在 `[sound:...]` 出现的位置放一个播放按钮。 */
    data class Sound(val name: String) : RenderBlock

    data object Divider : RenderBlock
}

object MiniHtml {

    private val VOID_TAGS = setOf("br", "img", "hr", "meta", "link", "input", "source")
    private val BLOCK_TAGS = setOf(
        "html", "body", "#root", "div", "p", "section", "article", "header", "footer", "main", "aside",
        "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "table", "tbody", "thead", "tr", "td", "th", "hr",
    )

    fun render(
        html: String,
        css: String = "",
        baseSizeSp: Float = 18f,
        scale: Float = 1f,
    ): List<RenderBlock> {
        val (body, styleText) = splitStyle(html)
        val rules = CssParser.parse(styleText + "\n" + css)
        val root = Parser(body).parse()

        // Anki 渲染结果不带外层 .card 容器，但模板 CSS 几乎都写在 .card 上；
        // 这里用一个虚拟根元素套用 .card / body 的样式（字号、颜色、对齐、行高）。
        val rootElement = HtmlNode.Element("#root", mapOf("class" to "card"))
        val rootDeclarations = CssParser.declarationsFor(rootElement, rules)
        val rootInline = CssParser.inlineStyle(
            declarations = rootDeclarations,
            tag = "body",
            parent = InlineStyle(),
            parentSizeSp = baseSizeSp,
            scale = scale,
        )
        val rootBlock = CssParser.blockStyle(
            declarations = rootDeclarations,
            parentAlign = HtmlAlign.START,
            parentSizeSp = baseSizeSp,
            scale = scale,
        )
        val ctx = Ctx(
            inline = rootInline,
            baseSizeSp = rootInline.sizeSp ?: baseSizeSp,
            align = rootBlock.align,
            lineHeight = rootBlock.lineHeight,
            scale = scale,
        )
        val out = mutableListOf<RenderBlock>()
        walk(root, ctx, rules, out, indent = 0f, bullet = null)
        return out.filterNot { block ->
            block is RenderBlock.Paragraph && block.spans.all { it.text.isBlank() }
        }
    }

    private data class Ctx(
        val inline: InlineStyle = InlineStyle(),
        val baseSizeSp: Float = 18f,
        val align: HtmlAlign = HtmlAlign.START,
        val listStyle: String? = null,
        val lineHeight: Float? = null,
        val scale: Float = 1f,
    )

    private fun walk(
        node: HtmlNode,
        ctx: Ctx,
        rules: List<CssParser.Rule>,
        out: MutableList<RenderBlock>,
        indent: Float,
        bullet: String?,
    ) {
        when (node) {
            is HtmlNode.Text -> {
                val (text, sounds) = splitSounds(normalize(node.text))
                if (text.isNotBlank()) {
                    out += RenderBlock.Paragraph(
                        spans = listOf(TextSpan(text.trim(), ctx.inline)),
                        style = BlockStyle(align = ctx.align),
                        indentDp = indent,
                        bullet = bullet,
                    )
                }
                sounds.forEach { out += RenderBlock.Sound(it) }
            }

            is HtmlNode.Element -> {
                val declarations = CssParser.declarationsFor(node, rules)
                if (declarations["display"]?.trim() == "none") return

                val tag = node.tag.lowercase()
                if (VOID_TAGS.contains(tag)) {
                    when (tag) {
                        "br" -> out += RenderBlock.Paragraph(
                            spans = listOf(TextSpan("\n", ctx.inline)),
                            style = BlockStyle(align = ctx.align),
                            indentDp = indent,
                        )

                        "hr" -> out += RenderBlock.Divider

                        "img" -> node.attributes["src"]?.let { src ->
                            out += RenderBlock.Image(decode(src), ctx.align)
                        }
                    }
                    return
                }

                val inline = CssParser.inlineStyle(
                    declarations = declarations,
                    tag = tag,
                    parent = ctx.inline,
                    parentSizeSp = ctx.baseSizeSp,
                    scale = ctx.scale,
                )
                val block = CssParser.blockStyle(
                    declarations = declarations,
                    parentAlign = ctx.align,
                    parentSizeSp = ctx.baseSizeSp,
                    scale = ctx.scale,
                )
                val blockWithLineHeight = block.copy(
                    lineHeight = block.lineHeight ?: ctx.lineHeight,
                    dividerTop = block.dividerTop ||
                        declarations.containsKey("border-top") ||
                        CssParser.pseudoDivider(node, rules, "before"),
                    dividerBottom = block.dividerBottom ||
                        declarations.containsKey("border-bottom") ||
                        CssParser.pseudoDivider(node, rules, "after"),
                )
                val nextCtx = ctx.copy(
                    inline = inline,
                    align = blockWithLineHeight.align,
                    listStyle = blockWithLineHeight.listStyle ?: ctx.listStyle,
                    lineHeight = blockWithLineHeight.lineHeight,
                )

                if (BLOCK_TAGS.contains(tag)) {
                    val inner = mutableListOf<RenderBlock>()
                    // 按文档顺序累积行内文字，遇到块级子节点/图片/音频就先落盘再继续，
                    // 否则「文字 + 子块 + 文字」会把后半段文字挪到子块前面。
                    val pending = mutableListOf<TextSpan>()
                    fun flush() {
                        if (pending.any { it.text.isNotBlank() }) {
                            inner += RenderBlock.Paragraph(
                                spans = pending.toList(),
                                style = blockWithLineHeight.withoutDecoration(),
                                indentDp = indent,
                                bullet = bullet,
                            )
                        }
                        pending.clear()
                    }

                    var index = 0
                    node.children.forEach { child ->
                        when (child) {
                            is HtmlNode.Text -> {
                                val (text, sounds) = splitSounds(normalize(child.text))
                                if (text.isNotEmpty()) pending += TextSpan(text, nextCtx.inline)
                                if (sounds.isNotEmpty()) {
                                    flush()
                                    sounds.forEach { inner += RenderBlock.Sound(it) }
                                }
                            }

                            is HtmlNode.Element -> {
                                val childDeclarations =
                                    CssParser.declarationsFor(child, rules)
                                if (childDeclarations["display"]?.trim() == "none") {
                                    return@forEach
                                }
                                val childTag = child.tag.lowercase()
                                when {
                                    childTag == "img" -> {
                                        flush()
                                        child.attributes["src"]?.let {
                                            inner += RenderBlock.Image(decode(it), block.align)
                                        }
                                    }

                                    childTag == "br" -> {
                                        pending += TextSpan("\n", nextCtx.inline)
                                    }

                                    BLOCK_TAGS.contains(childTag) -> {
                                        flush()
                                        val childBullet = when {
                                            childTag == "li" && tag == "ol" -> "${index + 1}."
                                            childTag == "li" -> "•"
                                            else -> null
                                        }
                                        if (childTag == "li") index++
                                        val childIndent =
                                            if (childTag == "li") indent + 16f else indent
                                        walk(
                                            child,
                                            nextCtx,
                                            rules,
                                            inner,
                                            childIndent,
                                            childBullet,
                                        )
                                    }

                                    else -> {
                                        val childInline = CssParser.inlineStyle(
                                            declarations = childDeclarations,
                                            tag = childTag,
                                            parent = nextCtx.inline,
                                            parentSizeSp = nextCtx.baseSizeSp,
                                            scale = nextCtx.scale,
                                        )
                                        val before = pending.size
                                        val sounds = mutableListOf<String>()
                                        pending += flattenInline(
                                            child,
                                            nextCtx.copy(inline = childInline),
                                            rules,
                                            sounds,
                                        )
                                        if (pending.size > before &&
                                            hasInlineGap(childDeclarations)
                                        ) {
                                            pending += TextSpan(" ", childInline)
                                        }
                                        if (sounds.isNotEmpty()) {
                                            flush()
                                            sounds.forEach { inner += RenderBlock.Sound(it) }
                                        }
                                        collectInlineImages(child, rules).forEach {
                                            flush()
                                            inner += RenderBlock.Image(it, block.align)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    flush()

                    if (blockWithLineHeight.hasDecoration() && inner.isNotEmpty()) {
                        out += RenderBlock.Group(blockWithLineHeight, inner)
                    } else {
                        out += inner
                    }
                } else {
                    // 行内元素（span 等）被提到顶层时只保留文字与对齐：
                    // 它们的背景色/内边距是给"小标签"用的，铺满整行会变成难看的色条。
                    val inlineSounds = mutableListOf<String>()
                    val spans = flattenInline(node, nextCtx, rules, inlineSounds)
                    if (spans.any { it.text.isNotBlank() }) {
                        out += RenderBlock.Paragraph(
                            spans = spans,
                            style = block.withoutDecoration(),
                            indentDp = indent,
                            bullet = bullet,
                        )
                    }
                    inlineSounds.forEach { out += RenderBlock.Sound(it) }
                    collectInlineImages(node, rules).forEach { out += RenderBlock.Image(it, block.align) }
                }
            }
        }
    }

    /** 从文本里拆出 `[sound:name]`，其余文本保留。 */
    private fun splitSounds(text: String): Pair<String, List<String>> {
        val sounds = mutableListOf<String>()
        val cleaned = text.replace(SOUND_TAG) { match ->
            val name = match.value.removePrefix("[sound:").removeSuffix("]")
            sounds += decode(name.trim())
            " "
        }
        return cleaned to sounds
    }

    /** 收集块内所有行内内容（遇到块级子节点就停，交给外层递归）。 */
    private fun flattenInline(
        node: HtmlNode.Element,
        ctx: Ctx,
        rules: List<CssParser.Rule>,
        sounds: MutableList<String> = mutableListOf(),
    ): List<TextSpan> {
        val spans = mutableListOf<TextSpan>()
        node.children.forEach { child ->
            when (child) {
                is HtmlNode.Text -> {
                    val (text, found) = splitSounds(normalize(child.text))
                    if (text.isNotEmpty()) spans += TextSpan(text, ctx.inline)
                    sounds += found
                }

                is HtmlNode.Element -> {
                    val tag = child.tag.lowercase()
                    when {
                        tag == "br" -> spans += TextSpan("\n", ctx.inline)
                        tag == "img" || BLOCK_TAGS.contains(tag) -> Unit
                        else -> {
                            val declarations = CssParser.declarationsFor(child, rules)
                            if (declarations["display"]?.trim() == "none") return@forEach
                            val inline = CssParser.inlineStyle(
                                declarations = declarations,
                                tag = tag,
                                parent = ctx.inline,
                                parentSizeSp = ctx.baseSizeSp,
                                scale = ctx.scale,
                            )
                            val before = spans.size
                            spans += flattenInline(child, ctx.copy(inline = inline), rules, sounds)
                            // 模板常用 margin/padding 做词性标签与释义之间的间距（如 span.pos），
                            // 我们不画 padding，就补一个空格，避免 "N-COUNTA hog" 粘在一起。
                            if (spans.size > before && hasInlineGap(declarations)) {
                                spans += TextSpan(" ", inline)
                            }
                        }
                    }
                }
            }
        }
        return spans
    }

    /** 行内元素的 margin/padding 是否给相邻文本制造了间距。 */
    private fun hasInlineGap(declarations: Map<String, String>): Boolean {
        fun positive(raw: String?): Boolean {
            val value = raw ?: return false
            return value.split(' ').any { token ->
                val number = token
                    .removeSuffix("px")
                    .removeSuffix("em")
                    .removeSuffix("rem")
                    .toFloatOrNull()
                number != null && number > 0f
            }
        }
        return positive(declarations["margin-right"]) ||
            positive(declarations["margin-left"]) ||
            positive(declarations["padding"]) ||
            positive(declarations["padding-right"]) ||
            positive(declarations["padding-left"])
    }

    private fun collectInlineImages(node: HtmlNode.Element, rules: List<CssParser.Rule>): List<String> {
        val images = mutableListOf<String>()
        node.children.forEach { child ->
            if (child is HtmlNode.Element) {
                val tag = child.tag.lowercase()
                when {
                    tag == "img" -> child.attributes["src"]?.let { images += decode(it) }
                    BLOCK_TAGS.contains(tag) -> Unit
                    else -> images += collectInlineImages(child, rules)
                }
            }
        }
        return images
    }

    private fun normalize(text: String): String =
        decodeEntities(stripTts(text)).replace(Regex("\\s+"), " ")

    /** TTS 标记保留内部文字、去掉标签；一期没有语音合成，只显示文字。 */
    private fun stripTts(text: String): String =
        text.replace(TTS_TAG, "$1").replace(TTS_OPEN_OR_CLOSE, "")

    /** 卡片模板里的 `&nbsp;` / `&amp;` / 数字实体，不转就会原样显示成 `&nbsp;`。 */
    private fun decodeEntities(raw: String): String = ENTITY.replace(raw) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                decodeCodePoint(body.substring(2), radix = 16) ?: match.value
            body.startsWith("#") ->
                decodeCodePoint(body.substring(1), radix = 10) ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }

    private fun decodeCodePoint(text: String, radix: Int): String? {
        val code = text.toIntOrNull(radix) ?: return null
        return runCatching { String(Character.toChars(code)) }.getOrNull()
    }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to "\u00a0",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
    )

    /** `[sound:xxx.mp3]` 转播放按钮；`[anki:tts]` 只去标签、保留内部文字。 */
    private val SOUND_TAG = Regex("\\[sound:[^\\]]+]", RegexOption.IGNORE_CASE)

    private val TTS_TAG = Regex(
        "\\[anki:tts[^\\]]*](.*?)\\[/anki:tts]",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TTS_OPEN_OR_CLOSE = Regex("\\[/?anki:tts[^\\]]*]", RegexOption.IGNORE_CASE)

    private fun decode(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    /**
     * 把 `<style>` 与 `<script>` 从正文里摘出来：
     * style 里的 CSS 拿去解析规则，script 直接丢弃（不执行 JS）。
     */
    private fun splitStyle(html: String): Pair<String, String> {
        val styleRegex = Regex("(?is)<style[^>]*>(.*?)</style>")
        val css = styleRegex.findAll(html).joinToString("\n") { it.groupValues[1] }
        val withoutStyle = html.replace(styleRegex, "")
        val withoutScript = withoutStyle.replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        return withoutScript to css
    }

    // ---- 解析器 ----

    private class Parser(private val input: String) {
        private var pos = 0

        fun parse(): HtmlNode.Element {
            val root = HtmlNode.Element("#root", emptyMap())
            val stack = ArrayDeque<HtmlNode.Element>().apply { add(root) }
            while (pos < input.length) {
                when {
                    input.startsWith("<!--", pos) -> {
                        val end = input.indexOf("-->", pos)
                        pos = if (end < 0) input.length else end + 3
                    }

                    input[pos] == '<' -> parseTag(stack)

                    else -> {
                        val next = input.indexOf('<', pos).let { if (it < 0) input.length else it }
                        val text = input.substring(pos, next)
                        if (text.isNotBlank()) stack.last().children += HtmlNode.Text(text)
                        pos = next
                    }
                }
            }
            return root
        }

        private fun parseTag(stack: ArrayDeque<HtmlNode.Element>) {
            val end = input.indexOf('>', pos).let { if (it < 0) input.length else it }
            val raw = input.substring(pos + 1, end).trim()
            pos = end + 1
            if (raw.isEmpty()) return
            if (raw.startsWith("/")) {
                if (stack.size > 1) stack.removeLast()
                return
            }
            val selfClosing = raw.endsWith("/")
            val content = raw.removeSuffix("/").trim()
            val space = content.indexOfFirst { it == ' ' || it == '\t' || it == '\n' }
            val tag = (if (space < 0) content else content.substring(0, space)).lowercase()
            val attrs = parseAttributes(if (space < 0) "" else content.substring(space))
            val element = HtmlNode.Element(tag, attrs)
            stack.last().children += element
            if (!selfClosing && !VOID_TAGS.contains(tag)) stack.addLast(element)
        }

        private fun parseAttributes(text: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")
                .findAll(text)
                .forEach { match ->
                    val value = match.groupValues[3].ifEmpty {
                        match.groupValues[4].ifEmpty { match.groupValues[5] }
                    }
                    map[match.groupValues[1].lowercase()] = value
                }
            return map
        }
    }
}
