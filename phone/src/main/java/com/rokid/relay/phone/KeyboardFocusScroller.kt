package com.rokid.relay.phone

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView

object KeyboardFocusScroller {
    private val main = Handler(Looper.getMainLooper())
    private val focusScrollDelaysMs = longArrayOf(80L, 280L)

    fun install(activity: Activity, root: View) {
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
        val rect = Rect(
            0,
            -input.dp(FOCUS_TOP_MARGIN_DP),
            input.width,
            input.height + input.dp(FOCUS_BOTTOM_MARGIN_DP),
        )
        scrollView.requestChildRectangleOnScreen(input, rect, false)
    }

    private fun View.parentScrollView(): ScrollView? {
        var current: View? = this
        while (current != null) {
            if (current is ScrollView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun View.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private const val FOCUS_TOP_MARGIN_DP = 12
    private const val FOCUS_BOTTOM_MARGIN_DP = 112
}
