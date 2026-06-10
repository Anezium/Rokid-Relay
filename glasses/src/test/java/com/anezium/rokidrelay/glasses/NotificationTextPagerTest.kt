package com.anezium.rokidrelay.glasses

import android.graphics.Paint
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationTextPagerTest {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
    }
    private val spec = NotificationTextPager.LayoutSpec(
        widthPx = 260,
        maxLines = 4,
        lineSpacingAddPx = 1f,
        lineSpacingMultiplier = 1.02f,
    )

    @Test
    fun thirdMessageMovesToNextPageWhenFirstPageWouldOverflow() {
        val text = listOf(
            "First sender writes enough words to take several measured layout lines on the glasses display.",
            "Second sender also writes enough words to take several measured layout lines on the glasses display.",
            "Third message must not be squeezed into the same popup and clipped halfway.",
        ).joinToString("\n")

        val pages = NotificationTextPager.pages(text, paint, spec)

        assertTrue(pages.size > 1)
        assertTrue(pages[0].contains("First sender"))
        assertFalse(pages[0].contains("Third message"))
        assertTrue(pages.drop(1).joinToString("\n").contains("Third message"))
    }

    @Test
    fun everyPageStaysWithinMeasuredLineBudget() {
        val text = listOf(
            "Alpha ".repeat(60),
            "Bravo ".repeat(60),
            "Charlie ".repeat(60),
        ).joinToString("\n")

        val pages = NotificationTextPager.pages(text, paint, spec)

        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertTrue(NotificationTextPager.measuredLineCount(page, paint, spec) <= spec.maxLines)
        }
    }

    @Test
    fun longSingleTokenIsSplitAcrossPages() {
        val text = "x".repeat(360)

        val pages = NotificationTextPager.pages(text, paint, spec)

        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertTrue(NotificationTextPager.measuredLineCount(page, paint, spec) <= spec.maxLines)
        }
    }

    @Test
    fun pageClampsIndex() {
        val text = "One\nTwo ".repeat(80)
        val pages = NotificationTextPager.pages(text, paint, spec)

        assertEquals(pages.last(), NotificationTextPager.page(text, 999, paint, spec))
        assertEquals(pages.first(), NotificationTextPager.page(text, -1, paint, spec))
    }

    @Test
    fun defaultPageCountMatchesRokidOverlayFallback() {
        val text = (1..30).joinToString("\n") { index ->
            "Message $index includes enough text to exercise page navigation on the glasses overlay."
        }
        val rokidPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * 1.5f
        }
        val rokidSpec = NotificationTextPager.LayoutSpec(widthPx = 414, maxLines = 9)

        assertEquals(
            NotificationTextPager.pageCount(text, rokidPaint, rokidSpec),
            NotificationTextPager.pageCount(text, fontSizeSp = 15f),
        )
        assertTrue(NotificationTextPager.pageCount(text, fontSizeSp = 15f) >= 4)
    }
}
