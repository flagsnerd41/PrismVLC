package com.example.myvlc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Slim iOS-style progress bar. At rest it is just a thin track with a bright fill.
 * Touch it and a liquid-glass lens grows out of the track, bending the video and the
 * track underneath it. Shows a time bubble while dragging.
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

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val fallbackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val fallbackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xAAFFFFFF.toInt()
        strokeWidth = 1f * d
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f * d
    }
    private val rect = RectF()

    private var backdropShader: BitmapShader? = null
    private var trackBitmap: Bitmap? = null
    private var trackCanvas: Canvas? = null
    private var glass: Shader? = null

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

    /**
     * The live video snapshot behind this view. [matrix] maps bitmap pixels to this
     * view's own coordinates. Only used while the glass lens is showing.
     */
    fun setBackdrop(bmp: Bitmap, matrix: Matrix) {
        if (expand <= 0.01f) return
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.setLocalMatrix(matrix)
        backdropShader = s
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trackBitmap?.recycle()
        if (w > 0 && h > 0) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            trackBitmap = bmp
            trackCanvas = Canvas(bmp)
        } else {
            trackBitmap = null
            trackCanvas = null
        }
    }

    private fun animateExpand(target: Float) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(expand, target)
        a.duration = 220
        a.interpolator = android.view.animation.OvershootInterpolator(1.4f)
        a.addUpdateListener {
            expand = (it.animatedValue as Float).coerceAtLeast(0f)
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
                    backdropShader = null
                    onSeekEnd?.invoke(shown)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Draws the slim track: faint full capsule, bright fill up to [x]. */
    private fun drawTrack(c: Canvas, x: Float) {
        val pad = 14f * d
        val left = pad
        val right = width - pad
        val cy = height - 26f * d
        val h = (5f + 3f * expand) * d
        val r = h / 2f
        rect.set(left, cy - h / 2f, right, cy + h / 2f)
        c.drawRoundRect(rect, r, r, inactivePaint)
        c.save()
        c.clipRect(left, cy - h, x, cy + h)
        c.drawRoundRect(rect, r, r, activePaint)
        c.restore()
    }

    @RequiresApi(33)
    private fun drawGlassThumb(
        canvas: Canvas, track: Bitmap, backdrop: BitmapShader,
        x: Float, cy: Float, tw: Float, th: Float
    ) {
        val sh = (glass as? RuntimeShader) ?: LiquidGlass.newSeekShader().also { glass = it }
        sh.setInputShader("backdrop", backdrop)
        sh.setInputShader("track", BitmapShader(track, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val l = x - tw / 2f
        val t = cy - th / 2f
        val small = minOf(tw, th)
        LiquidGlass.configure(
            sh,
            tw, th, l, t,
            small / 2f,
            tw * 0.40f,
            small * 0.5f,
            0.10f + 0.10f * expand,
            0.05f,
            d,
            expand,
            false,
            1f, 0.04f
        )
        glassPaint.shader = sh
        canvas.drawRect(l - 2f, t - 2f, l + tw + 2f, t + th + 2f, glassPaint)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 14f * d
        val cy = height - 26f * d
        val x = pad + (width - 2 * pad) * shown
        val tw = (6f + 36f * expand) * d
        val th = (10f + 36f * expand) * d

        drawTrack(canvas, x)

        if (expand > 0.01f) {
            val bd = backdropShader
            val tb = trackBitmap
            val tc = trackCanvas
            if (Build.VERSION.SDK_INT >= 33 && bd != null && tb != null && tc != null) {
                tb.eraseColor(Color.TRANSPARENT)
                drawTrack(tc, x)
                drawGlassThumb(canvas, tb, bd, x, cy, tw, th)
            } else {
                rect.set(x - tw / 2f, cy - th / 2f, x + tw / 2f, cy + th / 2f)
                canvas.drawRoundRect(rect, tw / 2f, tw / 2f, fallbackFill)
                canvas.drawRoundRect(rect, tw / 2f, tw / 2f, fallbackStroke)
            }
        }

        // time bubble while dragging
        if (expand > 0.05f) {
            val label = formatTime((shown * durationMs).toLong())
            val tw2 = textPaint.measureText(label) + 24f * d
            val bh = 28f * d
            val bx = x.coerceIn(tw2 / 2, width - tw2 / 2)
            val bottom = cy - th / 2f - 6f * d
            val a = expand.coerceIn(0f, 1f)
            bubblePaint.alpha = (204 * a).toInt()
            textPaint.alpha = (255 * a).toInt()
            rect.set(bx - tw2 / 2, bottom - bh, bx + tw2 / 2, bottom)
            canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
            canvas.drawText(label, bx, bottom - bh / 2 + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
