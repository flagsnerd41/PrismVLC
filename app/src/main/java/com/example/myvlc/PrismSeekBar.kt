package com.example.myvlc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * A progress bar that grows while you touch it, shows a glowing thumb
 * and a floating time bubble that follows your finger.
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

    private val accent: Int
    private val accentLight: Int
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    init {
        val tv = TypedValue()
        accent = if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true))
            tv.data else 0xFF7C9CFF.toInt()
        accentLight = ColorUtils.blendARGB(accent, Color.WHITE, 0.45f)
        ringPaint.color = accent
        textPaint.textSize = 13f * d
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
        val pad = 12f * d
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
        val pad = 12f * d
        val left = pad
        val right = width - pad
        val cy = height - 18f * d
        val h = (4f + 6f * expand) * d

        rect.set(left, cy - h / 2, right, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, inactivePaint)

        val x = left + (right - left) * shown
        if (x > left) {
            activePaint.shader = LinearGradient(
                left, 0f, maxOf(x, left + 1f), 0f, accent, accentLight, Shader.TileMode.CLAMP
            )
            rect.set(left, cy - h / 2, x, cy + h / 2)
            canvas.drawRoundRect(rect, h / 2, h / 2, activePaint)
        }

        if (expand > 0f) {
            glowPaint.color = ColorUtils.setAlphaComponent(accent, (70 * expand).toInt())
            canvas.drawCircle(x, cy, 20f * d * (0.6f + 0.4f * expand), glowPaint)
        }
        val tr = (6f + 5f * expand) * d
        canvas.drawCircle(x, cy, tr, thumbPaint)
        if (expand > 0f) {
            ringPaint.strokeWidth = 2.5f * d * expand
            canvas.drawCircle(x, cy, tr, ringPaint)
        }

        if (expand > 0.05f) {
            val label = formatTime((shown * durationMs).toLong())
            val tw = textPaint.measureText(label) + 24f * d
            val bh = 28f * d
            val bx = x.coerceIn(tw / 2, width - tw / 2)
            val bottom = cy - tr - 8f * d
            bubblePaint.alpha = (204 * expand).toInt()
            textPaint.alpha = (255 * expand).toInt()
            rect.set(bx - tw / 2, bottom - bh, bx + tw / 2, bottom)
            canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
            canvas.drawText(label, bx, bottom - bh / 2 + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
