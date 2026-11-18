package ink.inkdeck.ui.html

import org.junit.Assert.assertTrue
import org.junit.Test

/** 复现真实卡片（Charlotte's Web）答案面：内容必须能渲染出来。 */
class CharlotteAnswerTest {

    private val css = """
        .card { font-family: Arial; font-size: 20px; line-height: 1.6; }
        .card-type { font-size: 12px; color: #666; padding: 2px 6px; background: #f0f0f0; }
        .answer-area { margin: 5px 0 10px 0; padding: 20px 0; position: relative; }
        .answer-content { font-size: 16px; line-height: 1.6; padding-left: 5px; }
        span.pos { font-size:0.9em; margin-right:5px; padding:2px 4px; color:white; background-color:#0d47a1; }
        span.chn_tran { color:#0d47a1; }
        ul.sents { font-size:0.8em; margin:3px 0;padding:5px;background:rgba(13,71,161,0.1); }
        ul { background: none !important; border: 1px solid #ccc; }
    """.trimIndent()

    private fun flattenText(blocks: List<RenderBlock>): String = blocks.joinToString("|") { block ->
        when (block) {
            is RenderBlock.Paragraph -> block.spans.joinToString("") { it.text }
            is RenderBlock.Group -> flattenText(block.blocks)
            is RenderBlock.Sound -> ""
            is RenderBlock.Image -> ""
            RenderBlock.Divider -> ""
        }
    }

    private val answerHtml = """
        <div class="card">
          <div class="card-header">
            <div class="deck-name">English::Charlotte's Web</div>
            <div class="card-type">Definition</div>
          </div>
          <div class="answer-area" onclick="toggleTrans()">
            <div class="answer-content">
              <style>span.star {color: #FFBB00;}</style>
              <span class="pos">N-COUNT</span>
              <span class="tran"><span class="eng_tran">A <b>pitcher</b> is a jug.  </span><span class="chn_tran">（有柄有嘴的）罐，壶</span></span>
              <ul class="sents"><li class='sent'><span class='eng_sent'>...a <b>pitcher</b> of iced water.</span><span class='chn_sent'>一罐冰水</span></li></ul>
            </div>
          </div>
          <script>ldoce4_convertKPP();</script>
        </div>
    """.trimIndent()

    @Test
    fun answerContentRendersWithRealCss() {
        val realCss = java.io.File("src/test/resources/charlotte.css").readText()
        val blocks = MiniHtml.render(answerHtml, realCss)
        val text = flattenText(blocks)
        println("REAL_BLOCKS=${blocks.size} REAL_TEXT=$text")
        blocks.forEach { block ->
            println(
                "BLOCK " + when (block) {
                    is RenderBlock.Paragraph -> "P(text='${block.spans.joinToString("") { it.text }}' divTop=${block.style.dividerTop})"
                    is RenderBlock.Group -> "G(children=${block.blocks.size}) " +
                        block.blocks.joinToString { inner ->
                            if (inner is RenderBlock.Paragraph) "P('${inner.spans.joinToString("") { it.text }}')" else inner::class.simpleName!!
                        }
                    is RenderBlock.Image -> "IMG(${block.name})"
                    is RenderBlock.Sound -> "SOUND(${block.name})"
                    RenderBlock.Divider -> "HR"
                },
            )
        }
        assertTrue("真实 CSS 下答案面应有内容，实际块数 ${blocks.size}", blocks.isNotEmpty())
        assertTrue("应包含释义：$text", text.contains("jug"))
        assertTrue("应包含中文释义：$text", text.contains("罐"))
    }

    @Test
    fun answerContentRenders() {
        val blocks = MiniHtml.render(answerHtml, css)
        val text = flattenText(blocks)
        println("BLOCKS=${blocks.size} TEXT=$text")
        assertTrue("答案面应有内容，实际块数 ${blocks.size}", blocks.isNotEmpty())
        assertTrue("应包含释义：$text", text.contains("jug"))
        assertTrue("应包含中文释义：$text", text.contains("罐"))
    }
}
