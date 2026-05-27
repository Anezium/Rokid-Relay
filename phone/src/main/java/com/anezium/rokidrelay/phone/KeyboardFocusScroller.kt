package com.anezium.rokidrelay.phone

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView

object KeyboardFocusScroller {
    private val main = Handler(Looper.getMainLooper())
    private val focusScrollDelaysMs = longArrayOf(80L, 220L, 420L)

    fun install(activity: Activity, root: View) {
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root.findScrollViews().forEach { scrollView ->
            scrollView.clipToPadding = false
            scrollView.installImePadding()
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is EditText) scheduleScrollIntoView(newFocus)
        }
    }

    private fun scheduleScrollIntoView(input: EditText) {
        focusScrollDelaysMs.forEach { delayMs ->
            main.postDelayed({
                if (input.hasFocus() && input.isShown) scrollIntoView(input)
            }, delayMs)
        }
    }

    private fun scrollIntoView(input: EditText) {
        val scrollView = input.parentScrollView() ?: return
        val inputRect = Rect(0, 0, input.width, input.height)
        scrollView.offsetDescendantRectToMyCoords(input, inputRect)

        val topLimit = scrollView.scrollY + input.dp(FOCUS_TOP_MARGIN_DP)
        val bottomLimit = scrollView.scrollY +
            scrollView.height -
            scrollView.paddingBottom -
            input.dp(FOCUS_BOTTOM_MARGIN_DP)

        val nextScrollY = when {
            inputRect.bottom > bottomLimit -> scrollView.scrollY + (inputRect.bottom - bottomLimit)
            inputRect.top < topLimit -> scrollView.scrollY - (topLimit - inputRect.top)
            else -> scrollView.scrollY
        }.coerceAtLeast(0)

        if (nextScrollY != scrollView.scrollY) {
            scrollView.smoothScrollTo(0, nextScrollY)
        }
    }

    private fun ScrollView.installImePadding() {
        val baseLeft = paddingLeft
        val baseTop = paddingTop
        val baseRight = paddingRight
        val baseBottom = paddingBottom
        setOnApplyWindowInsetsListener { view, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            val keyboardBottom = (ime - bars).coerceAtLeast(0)
            view.setPadding(
                baseLeft,
                baseTop,
                baseRight,
                baseBottom + keyboardBottom + view.dp(KEYBOARD_SCROLL_ROOM_DP),
            )
            val focus = view.rootView.findFocus()
            if (focus is EditText) scheduleScrollIntoView(focus)
            insets
        }
        requestApplyInsets()
    }

    private fun View.parentScrollView(): ScrollView? {
        var current: View? = this
        while (current != null) {
            if (current is ScrollView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun View.findScrollViews(): List<ScrollView> {
        val result = mutableListOf<ScrollView>()
        fun visit(view: View) {
            if (view is ScrollView) result += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
        return result
    }

    private fun View.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private const val FOCUS_TOP_MARGIN_DP = 12
    private const val FOCUS_BOTTOM_MARGIN_DP = 24
    private const val KEYBOARD_SCROLL_ROOM_DP = 28
}
