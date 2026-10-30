package com.example.myvlc

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
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.min

/**
 * Glass progress bar. The whole bar sits in a liquid-glass capsule; touch it and a glass lens
 * springs out of the track, stretches as you drag (like a drop of liquid) and bends the video
 * and the track beneath it. Shows a time bubble while dragging.
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
    private var lensX = 0f
    private var stretch = 0f

    private val expandHolder = FloatValueHolder(0f)
    private val expandSpring = SpringAnimation(expandHolder)

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val fallbackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fallbackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x88FFFFFF.toInt()
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
    private var pillGlass: Shader? = null
    private var lensGlass: Shader? = null

    init {
        expandSpring.setSpring(SpringForce(0f).setDampingRatio(0.5f).setStiffness(380f))
        expandSpring.setMinimumVisibleChange(0.002f)
        expandSpring.addUpdateListener { _, value, _ ->
            expand = value.coerceAtLeast(0f)
            invalidate()
        }
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

    /**
     * The live video snapshot behind this view. [matrix] maps bitmap pixels to this
     * view's own coordinates.
     */
    fun setBackdrop(bmp: Bitmap, matrix: Matrix) {
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        expandSpring.cancel()
    }

    private fun animateExpand(target: Float) {
        val speed = GlassSettings.animSpeed
        expandSpring.spring.setStiffness(380f * speed * speed)
        expandSpring.animateToFinalPosition(target)
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
                lensX = thumbCenterX()
                stretch = 0f
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

    /** Draws the slim track: faint full capsule, bright fill up to [x]. */
    private fun drawTrack(c: Canvas, x: Float) {
        val pad = 14f * d
        val left = pad
        val right = width - pad
        val cy = height - 26f * d
        val h = (5f + 3f * expand.coerceAtMost(1.2f)) * d
        val r = h / 2f
        rect.set(left, cy - h / 2f, right, cy + h / 2f)
        c.drawRoundRect(rect, r, r, inactivePaint)
        c.save()
        c.clipRect(left, cy - h, x, cy + h)
        c.drawRoundRect(rect, r, r, activePaint)
        c.restore()
    }

    /** The glass capsule the whole bar sits in. */
    @RequiresApi(33)
    private fun drawPill(canvas: Canvas, empty: Bitmap, backdrop: BitmapShader, cy: Float) {
        val sh = (pillGlass as? RuntimeShader) ?: LiquidGlass.newSeekShader().also { pillGlass = it }
        sh.setInputShader("backdrop", backdrop)
        sh.setInputShader("track", BitmapShader(empty, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val h = 40f * d
        val w = width - 4f * d
        val l = 2f * d
        val t = cy - h / 2f
        LiquidGlass.configure(
            sh, w, h, l, t, h / 2f,
            h * 0.30f * GlassSettings.refraction,
            h * 0.5f, 0f,
            GlassSettings.dispersion, d, 0f, false,
            0f, GlassSettings.tint
        )
        glassPaint.shader = sh
        canvas.drawRect(l - 2f, t - 2f, l + w + 2f, t + h + 2f, glassPaint)
    }

    /** The lens that springs out of the track while dragging. */
    @RequiresApi(33)
    private fun drawLens(
        canvas: Canvas, track: Bitmap, backdrop: BitmapShader,
        x: Float, cy: Float, tw: Float, th: Float
    ) {
        val sh = (lensGlass as? RuntimeShader) ?: LiquidGlass.newSeekShader().also { lensGlass = it }
        sh.setInputShader("backdrop", backdrop)
        sh.setInputShader("track", BitmapShader(track, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val l = x - tw / 2f
        val t = cy - th / 2f
        val small = min(tw, th)
        LiquidGlass.configure(
            sh, tw, th, l, t, small / 2f,
            tw * 0.40f * GlassSettings.refraction,
            small * 0.5f,
            0.10f + 0.10f * expand.coerceAtMost(1f),
            GlassSettings.dispersion, d,
            expand.coerceAtMost(1f),
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
        val glassOk = Build.VERSION.SDK_INT >= 33 && GlassSettings.liquid

        // the lens trails the finger a little and stretches with speed, like a drop of liquid
        val prevLens = lensX
        if (expand <= 0.02f && !dragging) lensX = x else lensX += (x - lensX) * 0.4f
        val vel = abs(lensX - prevLens)
        stretch += (min(vel / (14f * d), 1f) - stretch) * 0.25f
        val tw = (6f + 36f * expand) * d * (1f + 0.38f * stretch)
        val th = (10f + 36f * expand) * d * (1f - 0.10f * stretch)

        val bd = backdropShader
        val tb = trackBitmap
        val tc = trackCanvas
        val haveGlass = glassOk && bd != null && tb != null && tc != null

        // 1. no glass capsule any more: only the slim progress bar

        // 2. the slim track
        drawTrack(canvas, x)

        // 3. the lens
        if (expand > 0.01f) {
            if (haveGlass) {
                tb!!.eraseColor(Color.TRANSPARENT)
                drawTrack(tc!!, x)
                drawLens(canvas, tb, bd!!, lensX, cy, tw, th)
            } else {
                rect.set(lensX - tw / 2f, cy - th / 2f, lensX + tw / 2f, cy + th / 2f)
                canvas.drawRoundRect(rect, tw / 2f, tw / 2f, fallbackFill)
                canvas.drawRoundRect(rect, tw / 2f, tw / 2f, fallbackStroke)
            }
            if (abs(x - lensX) > 0.5f || stretch > 0.02f) postInvalidateOnAnimation()
        }

        // time bubble while dragging
        if (expand > 0.05f) {
            val label = formatTime((shown * durationMs).toLong())
            val bw = textPaint.measureText(label) + 24f * d
            val bh = 28f * d
            val bx = lensX.coerceIn(bw / 2, width - bw / 2)
            val bottom = cy - maxOf(th, 40f * d) / 2f - 6f * d
            val a = expand.coerceIn(0f, 1f)
            bubblePaint.alpha = (204 * a).toInt()
            textPaint.alpha = (255 * a).toInt()
            rect.set(bx - bw / 2, bottom - bh, bx + bw / 2, bottom)
            canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
            canvas.drawText(label, bx, bottom - bh / 2 + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
