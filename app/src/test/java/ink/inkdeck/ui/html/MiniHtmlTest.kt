package ink.inkdeck.ui.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T005 的排版回归：HTML 必须还原成结构，而不是压成一段纯文本。
 * 这里只测解析层（纯 JVM，不需要设备）。
 */
class MiniHtmlTest {

    @Test
    fun blockElementsBecomeSeparateParagraphs() {
        val html = """
            <div class="header">English::Charlotte's Web</div>
            <div><b>hog</b> /hɒɡ/ ★☆☆☆☆</div>
            <div>N-COUNT A hog is a pig.</div>
        """.trimIndent()

        val blocks = MiniHtml.render(html)
        val paragraphs = blocks.filterIsInstance<RenderBlock.Paragraph>()

        assertEquals("三个 div 应变成三段", 3, paragraphs.size)
        assertEquals("English::Charlotte's Web", paragraphs[0].text())
        assertTrue("第二段应包含粗体的 hog", paragraphs[1].spans.any { it.style.bold && it.text.contains("hog") })
        assertTrue(paragraphs[2].text().startsWith("N-COUNT"))
    }

    @Test
    fun inlineSiblingsStayInOneParagraph() {
        val html = "<div>前缀 <b>加粗</b> 后缀 <i>斜体</i></div>"
        val paragraphs = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>()
        assertEquals(1, paragraphs.size)
        val text = paragraphs.single().text()
        assertTrue(text.contains("前缀"))
        assertTrue(text.contains("加粗"))
        assertTrue(text.contains("后缀"))
        assertEquals(1, paragraphs.single().spans.count { it.style.italic })
    }

    @Test
    fun brKeepsLineBreaks() {
        val html = "<div>第一行<br>第二行</div>"
        val paragraph = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>().single()
        assertTrue("换行应保留为 \\n", paragraph.text().contains("\n"))
    }

    @Test
    fun htmlEntitiesAreDecoded() {
        val html = "<div>a&nbsp;b &amp; c &lt;d&gt; &#65;</div>"
        val text = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>().single().text()
        assertEquals("a\u00a0b & c <d> A", text)
    }

    @Test
    fun inlineMarginsBecomeSpaces() {
        val html = "<div><span style=\"padding: 0 4px\">ADJ</span><span>wet</span></div>"
        val text = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>().single().text()
        assertTrue("行内 padding 应折算成空格：$text", text.contains("ADJ wet"))
    }

    @Test
    fun listItemsGetBulletsAndContainerBorderBecomesGroup() {
        val html = """
            <ul class="examples">
              <li>We picked the corn by hand.</li>
              <li>Johnny Warrick has a hog farm.</li>
            </ul>
        """.trimIndent()
        val css = "ul.examples { border: 1px solid #000000; padding: 8px; }"

        val blocks = MiniHtml.render(html, css)
        val group = blocks.filterIsInstance<RenderBlock.Group>().singleOrNull()
        assertTrue("带边框的 ul 应包成 Group", group != null)
        assertEquals(1f, group!!.style.borderWidth)
        assertEquals(8f, group.style.padding)

        val items = group.blocks.filterIsInstance<RenderBlock.Paragraph>()
        assertEquals(2, items.size)
        assertEquals("•", items[0].bullet)
        assertTrue(items[0].text().contains("We picked the corn"))
    }

    @Test
    fun classSelectorFromStyleTagApplies() {
        val html = """
            <style>.big { font-size: 28px; font-weight: bold; }</style>
            <div class="big">大标题</div>
        """.trimIndent()
        val paragraph = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>().single()
        val span = paragraph.spans.first { it.text.contains("大标题") }
        assertTrue("class 选择器的粗体应生效", span.style.bold)
        assertEquals(28f, span.style.sizeSp)
    }

    @Test
    fun nestedBlocksArePreserved() {
        val html = "<div>段落一<div>段落二</div></div>"
        val texts = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>().map { it.text() }
        assertTrue("父块文字应在", texts.any { it.contains("段落一") })
        assertTrue("嵌套子块文字应在", texts.any { it.contains("段落二") })
    }

    @Test
    fun imageIsEmittedAsItsOwnBlock() {
        val html = "<div>看图：<img src=\"foo%20bar.png\"></div>"
        val blocks = MiniHtml.render(html)
        val image = blocks.filterIsInstance<RenderBlock.Image>().singleOrNull()
        assertTrue("图片应单独成块", image != null)
        assertEquals("文件名应做百分号解码", "foo bar.png", image!!.name)
    }

    @Test
    fun cardCssAppliesToRenderedContent() {
        val css = ".card { font-size: 20px; line-height: 1.5; text-align: center; }"
        val block = MiniHtml.render("<div>居中文本</div>", css)
            .filterIsInstance<RenderBlock.Paragraph>().single()
        assertEquals(HtmlAlign.CENTER, block.style.align)
        assertEquals(1.5f, block.style.lineHeight)
        assertEquals(20f, block.spans.first().style.sizeSp)
    }

    @Test
    fun soundTagsBecomeInlinePlayButtons() {
        val blocks = MiniHtml.render("<div>★☆☆☆☆[sound:foo_bar_0.mp3]</div>")
        val paragraph = blocks.filterIsInstance<RenderBlock.Paragraph>().single()
        assertTrue("正文不应出现 [sound:" , !paragraph.text().contains("[sound:"))
        assertTrue(paragraph.text().contains("★"))
        val sound = blocks.filterIsInstance<RenderBlock.Sound>().singleOrNull()
        assertTrue("应生成一个播放块", sound != null)
        assertEquals("foo_bar_0.mp3", sound!!.name)
    }

    @Test
    fun scriptContentIsDropped() {
        val html = "<div>正文</div><script>ldoce4_convertKPP();</script>"
        val text = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>().joinToString("") { it.text() }
        assertTrue("script 不应作为正文：$text", !text.contains("ldoce4"))
    }

    @Test
    fun pseudoElementBeforeBecomesTopDivider() {
        val css = ".sentence::before { content: \"\"; height: 1px; background: #ccc; }"
        val group = MiniHtml.render("<div class=\"sentence\">句子</div>", css)
            .filterIsInstance<RenderBlock.Group>().single()
        assertTrue("::before 应折算为上分隔线", group.style.dividerTop)
    }

    @Test
    fun borderBottomBecomesBottomDivider() {
        val css = ".card-header { border-bottom: 1px solid #e0e0e0; }"
        val group = MiniHtml.render("<div class=\"card-header\">标题</div>", css)
            .filterIsInstance<RenderBlock.Group>().single()
        assertTrue("border-bottom 应折算为下分隔线", group.style.dividerBottom)
    }

    @Test
    fun mixedTextAndBlockKeepsDocumentOrder() {
        val html = "<div>前文<p>段落</p>后文</div>"
        val texts = MiniHtml.render(html)
            .filterIsInstance<RenderBlock.Paragraph>()
            .map { it.text() }
        assertEquals(listOf("前文", "段落", "后文"), texts)
    }

    @Test
    fun imageStaysBetweenSurroundingText() {
        val blocks = MiniHtml.render("<div>前文<img src=\"a.png\">后文</div>")
        val order = blocks.map { block ->
            when (block) {
                is RenderBlock.Paragraph -> "text"
                is RenderBlock.Image -> "image"
                else -> "other"
            }
        }
        assertEquals(listOf("text", "image", "text"), order)
    }

    @Test
    fun ttsTagsAreStrippedButTextIsKept() {
        val html = "<div>[anki:tts lang=en_US]hello[/anki:tts] world</div>"
        val text = MiniHtml.render(html).filterIsInstance<RenderBlock.Paragraph>().single().text()
        assertEquals("hello world", text)
    }

    @Test
    fun fontScaleScalesCssPx() {
        val scale = 26f / 18f
        val body = MiniHtml.render(
            html = "<div>x</div>",
            css = ".card { font-size: 20px; }",
            baseSizeSp = 26f,
            scale = scale,
        ).filterIsInstance<RenderBlock.Paragraph>().single()
        assertEquals(20f * scale, body.spans.first().style.sizeSp!!, 0.01f)

        val tag = MiniHtml.render(
            html = "<div><span class=\"tag\">x</span></div>",
            css = ".tag { font-size: 10px; }",
            baseSizeSp = 26f,
            scale = scale,
        ).filterIsInstance<RenderBlock.Paragraph>().single()
        assertEquals(10f * scale, tag.spans.first().style.sizeSp!!, 0.01f)
    }

    private fun RenderBlock.Paragraph.text(): String = spans.joinToString("") { it.text }
}
