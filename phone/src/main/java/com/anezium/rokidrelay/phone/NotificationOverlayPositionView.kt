package com.anezium.rokidrelay.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class NotificationOverlayPositionView(context: Context) : View(context) {
    var yOffsetDp: Int = NotificationOverlayPositionStore.DEFAULT_Y_OFFSET_DP
        set(value) {
            field = NotificationOverlayPositionStore.sanitizeYOffsetDp(value)
            invalidate()
        }

    var onYOffsetChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val textScale = density * resources.configuration.fontScale
    private val screenRect = RectF()
    private val popupRect = RectF()
    private val screenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SCREEN
        style = Paint.Style.FILL
    }
    private val screenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SCREEN_STROKE
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRID
        strokeWidth = density
    }
    private val popupFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_POPUP_FILL
        style = Paint.Style.FILL
    }
    private val popupStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_POPUP_STROKE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        textSize = 13f * textScale
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_MUTED
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * textScale
    }

    private var scale = 1f
    private var dragging = false
    private var dragOffsetPx = 0f

    init {
        isClickable = true
        contentDescription = "Notification display position"
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(dp(220))
        val screenHeight = (availableWidth * SCREEN_HEIGHT_DP / SCREEN_WIDTH_DP).roundToInt()
        val desiredHeight = paddingTop + screenHeight + paddingBottom
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        scale = minOf(
            availableWidth / SCREEN_WIDTH_DP,
            availableHeight / SCREEN_HEIGHT_DP,
        )
        val screenWidth = SCREEN_WIDTH_DP * scale
        val screenHeight = SCREEN_HEIGHT_DP * scale
        val left = paddingLeft + (availableWidth - screenWidth) / 2f
        val top = paddingTop + (availableHeight - screenHeight) / 2f
        screenRect.set(left, top, left + screenWidth, top + screenHeight)

        canvas.drawRoundRect(screenRect, dp(10).toFloat(), dp(10).toFloat(), screenFill)
        drawGrid(canvas)
        canvas.drawRoundRect(screenRect, dp(10).toFloat(), dp(10).toFloat(), screenStroke)

        val popupWidth = POPUP_WIDTH_DP * scale
        val popupHeight = POPUP_HEIGHT_DP * scale
        val popupLeft = screenRect.centerX() - popupWidth / 2f
        val popupTop = screenRect.top + yOffsetDp * scale
        popupRect.set(popupLeft, popupTop, popupLeft + popupWidth, popupTop + popupHeight)

        canvas.drawRoundRect(popupRect, dp(4).toFloat(), dp(4).toFloat(), popupFill)
        canvas.drawRoundRect(popupRect, dp(4).toFloat(), dp(4).toFloat(), popupStroke)
        drawCenteredText(canvas, "Notification popup", popupRect.centerY() - dp(6), labelPaint)
        drawCenteredText(canvas, "${yOffsetDp}dp from top", popupRect.centerY() + dp(13), bodyPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!screenRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragOffsetPx = if (popupRect.contains(event.x, event.y)) {
                    event.y - popupRect.top
                } else {
                    popupRect.height() / 2f
                }
                updateYOffsetFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateYOffsetFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateYOffsetFromTouch(y: Float) {
        if (scale <= 0f) return
        val rawOffset = ((y - screenRect.top - dragOffsetPx) / scale).roundToInt()
        val cleanOffset = NotificationOverlayPositionStore.sanitizeYOffsetDp(rawOffset)
        if (cleanOffset == yOffsetDp) return
        yOffsetDp = cleanOffset
        onYOffsetChanged?.invoke(cleanOffset)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = dp(12).toFloat()
        var x = screenRect.left + step
        while (x < screenRect.right) {
            canvas.drawLine(x, screenRect.top, x, screenRect.bottom, gridPaint)
            x += step
        }
        var y = screenRect.top + step
        while (y < screenRect.bottom) {
            canvas.drawLine(screenRect.left, y, screenRect.right, y, gridPaint)
            y += step
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, centerY: Float, paint: Paint) {
        val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, popupRect.centerX(), baseline, paint)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val SCREEN_WIDTH_DP = 480f
        const val SCREEN_HEIGHT_DP = 640f
        const val POPUP_WIDTH_DP = 460f
        const val POPUP_HEIGHT_DP = 126f
        val COLOR_SCREEN: Int = Color.rgb(6, 14, 9)
        val COLOR_SCREEN_STROKE: Int = Color.rgb(56, 84, 64)
        val COLOR_GRID: Int = Color.argb(32, 132, 178, 145)
        val COLOR_POPUP_FILL: Int = Color.argb(28, 113, 255, 151)
        val COLOR_POPUP_STROKE: Int = Color.rgb(113, 255, 151)
        val COLOR_TEXT: Int = Color.rgb(224, 255, 232)
        val COLOR_MUTED: Int = Color.rgb(132, 178, 145)
    }
}
