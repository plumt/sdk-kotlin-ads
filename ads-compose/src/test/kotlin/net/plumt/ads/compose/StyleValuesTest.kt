package net.plumt.ads.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StyleValuesTest {
    private fun style(border: Color? = null) = NativeAdRowStyle(
        minHeight = 72.dp, contentPadding = PaddingValues(12.dp), iconSize = 32.dp, iconCornerRadius = 8.dp,
        background = Color.White, cornerRadius = 18.dp, borderColor = border, titleColor = Color.Black, titleSize = 15.sp,
        bodyColor = Color.Gray, bodySize = 13.sp, ctaBackground = Color.Blue, ctaContentColor = Color.White, ctaCornerRadius = 16.dp,
        badgeBackground = Color.LightGray, badgeContentColor = Color.DarkGray, showDividerTop = true, showDividerBottom = false,
        dividerColor = Color.Gray,
    )

    @Test fun rowStyleKeepsEveryValueAndCopies() {
        val s = style()
        assertEquals(72.dp, s.minHeight)
        assertEquals(32.dp, s.iconSize)
        assertEquals(8.dp, s.iconCornerRadius)
        assertEquals(18.dp, s.cornerRadius)
        assertNull(s.borderColor)
        assertEquals(15.sp, s.titleSize)
        assertEquals(13.sp, s.bodySize)
        assertEquals(Color.Blue, s.ctaBackground)
        assertEquals(Color.White, s.ctaContentColor)
        assertEquals(16.dp, s.ctaCornerRadius)
        assertEquals(Color.LightGray, s.badgeBackground)
        assertEquals(Color.DarkGray, s.badgeContentColor)
        assertEquals(true, s.showDividerTop)
        assertEquals(false, s.showDividerBottom)
        assertEquals(Color.Gray, s.dividerColor)
        assertEquals(Color.White, s.background)
        assertEquals(Color.Black, s.titleColor)
        assertEquals(Color.Gray, s.bodyColor)
        assertEquals(PaddingValues(12.dp), s.contentPadding)
        assertEquals(s, style())
        assertNotEquals(s, style(border = Color.Red))
        assertEquals(Color.Red, s.copy(borderColor = Color.Red).borderColor)
        assertEquals(s.hashCode(), style().hashCode())
    }

    @Test fun colorsKeepValues() {
        val c = NativeAdColors(Color.Black, Color.Gray, Color.Blue, Color.White)
        assertEquals(Color.Black, c.headline)
        assertEquals(Color.Gray, c.body)
        assertEquals(Color.Blue, c.ctaBackground)
        assertEquals(Color.White, c.ctaText)
    }
}
