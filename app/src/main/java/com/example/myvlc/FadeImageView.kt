package com.example.myvlc

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Image that fades from transparent (top) to solid (bottom), with a light dark tint. */
class FadeImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val tintPaint = Paint().apply { color = 0x40000000 }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val save = canvas.saveLayer(0f, 0f, w, h, null)
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, w, h, tintPaint)
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restoreToCount(save)
    }
}
