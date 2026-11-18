package ink.inkdeck.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 默认字号分档的边界回归：断点左闭右开，超大屏不继续放大。 */
class InkWidthClassTest {

    private fun body(widthDp: Int) = defaultBodySizeSp(widthDp)

    @Test
    fun compactBelow600Uses18() {
        assertEquals(18, body(0))
        assertEquals(18, body(359))
        assertEquals(18, body(408))
        assertEquals(18, body(576))
        assertEquals(18, body(599))
        assertEquals(InkWidthClass.Compact, inkWidthClassOf(599))
    }

    @Test
    fun medium600To839Uses22() {
        assertEquals(22, body(600))
        assertEquals(22, body(653))
        assertEquals(22, body(768))
        assertEquals(22, body(839))
        assertEquals(InkWidthClass.Medium, inkWidthClassOf(600))
    }

    @Test
    fun expanded840To1199Uses26() {
        assertEquals(26, body(840))
        assertEquals(26, body(906))
        assertEquals(26, body(989))
        assertEquals(26, body(1199))
        assertEquals(InkWidthClass.Expanded, inkWidthClassOf(840))
    }

    @Test
    fun extraLargeFrom1200AlsoUses26() {
        assertEquals(26, body(1200))
        assertEquals(26, body(1277))
        assertEquals(26, body(1702))
        assertEquals(InkWidthClass.ExtraLarge, inkWidthClassOf(1200))
    }
}
