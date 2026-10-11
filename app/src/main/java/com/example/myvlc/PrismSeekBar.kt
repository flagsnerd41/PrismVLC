package com.example.myvlc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Chunky rounded progress bar: a filled track, a gap, a slim vertical handle,
 * a gap, then the rest of the track with a dot at the end. Shows a time bubble while dragging.
 */
class PrismSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var durationMs: Long = 0L
    var onSeekStart: (() -> Unit)? = null
    var onSeekPreview: ((Float) -> Unit)? = null
    var onSeekEnd: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var shown = 0f
    private var dragging = false
    private var expand = 0f
    private var animator: ValueAnimator? = null

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private val path = Path()

    init {
        val accent = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF8AB4FF.toInt())
        val container = themeColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF0F3F9F.toInt())
        activePaint.color = accent
        thumbPaint.color = accent
        inactivePaint.color = ColorUtils.setAlphaComponent(container, 235)
        textPaint.textSize = 13f * d
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    /** Horizontal centre of the handle, in this view's own coordinates. */
    fun thumbCenterX(): Float {
        val pad = 14f * d
        return pad + (width - 2 * pad) * shown
    }

    /** Called by the player to move the bar (ignored while you are dragging). */
    fun updateProgress(p: Float) {
        if (!dragging) {
            shown = p.coerceIn(0f, 1f)
            invalidate()
        }
    }

    private fun animateExpand(target: Float) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(expand, target)
        a.duration = 160
        a.addUpdateListener {
            expand = it.animatedValue as Float
            invalidate()
        }
        animator = a
        a.start()
    }

    private fun fraction(x: Float): Float {
        val pad = 14f * d
        val w = width - 2 * pad
        return if (w <= 0f) 0f else ((x - pad) / w).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                shown = fraction(e.x)
                animateExpand(1f)
                onSeekStart?.invoke()
                onSeekPreview?.invoke(shown)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    shown = fraction(e.x)
                    onSeekPreview?.invoke(shown)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    shown = fraction(e.x)
                    animateExpand(0f)
                    onSeekEnd?.invoke(shown)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 14f * d
        val left = pad
        val right = width - pad
        val cy = height - 26f * d
        val h = 16f * d
        val outer = h / 2
        val inner = 3f * d
        val thumbW = (5f - 2f * expand) * d
        val thumbH = (40f + 4f * expand) * d
        val gap = 6f * d
        val x = left + (right - left) * shown

        val activeEnd = x - thumbW / 2 - gap
        val inactiveStart = x + thumbW / 2 + gap

        // filled part (round on the left, soft corners next to the handle)
        if (activeEnd > left + 1f) {
            rect.set(left, cy - h / 2, activeEnd, cy + h / 2)
            path.reset()
            path.addRoundRect(
                rect,
                floatArrayOf(outer, outer, inner, inner, inner, inner, outer, outer),
                Path.Direction.CW
            )
            canvas.drawPath(path, activePaint)
        }

        // remaining part (soft corners next to the handle, round on the right) + end dot
        if (right > inactiveStart + 1f) {
            rect.set(inactiveStart, cy - h / 2, right, cy + h / 2)
            path.reset()
            path.addRoundRect(
                rect,
                floatArrayOf(inner, inner, outer, outer, outer, outer, inner, inner),
                Path.Direction.CW
            )
            canvas.drawPath(path, inactivePaint)
            if (right - inactiveStart > h) {
                canvas.drawCircle(right - outer, cy, 3f * d, thumbPaint)
            }
        }

        // handle
        rect.set(x - thumbW / 2, cy - thumbH / 2, x + thumbW / 2, cy + thumbH / 2)
        canvas.drawRoundRect(rect, thumbW / 2, thumbW / 2, thumbPaint)

        // time bubble while dragging
        if (expand > 0.05f) {
            val label = formatTime((shown * durationMs).toLong())
            val tw = textPaint.measureText(label) + 24f * d
            val bh = 28f * d
            val bx = x.coerceIn(tw / 2, width - tw / 2)
            val bottom = cy - thumbH / 2 - 6f * d
            bubblePaint.alpha = (204 * expand).toInt()
            textPaint.alpha = (255 * expand).toInt()
            rect.set(bx - tw / 2, bottom - bh, bx + tw / 2, bottom)
            canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
            canvas.drawText(label, bx, bottom - bh / 2 + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
