package com.example.myvlc

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

/** Spring-based motion: everything overshoots a little and settles, like iOS. */
object Fluid {

    private val running =
        WeakHashMap<View, HashMap<DynamicAnimation.ViewProperty, SpringAnimation>>()

    fun spring(
        view: View,
        prop: DynamicAnimation.ViewProperty,
        to: Float,
        damping: Float = 0.6f,
        stiffness: Float = 380f,
        from: Float? = null,
        delayMs: Long = 0L
    ) {
        val speed = GlassSettings.animSpeed
        val map = running.getOrPut(view) { HashMap() }
        map[prop]?.cancel()
        if (from != null) prop.setValue(view, from)
        val start = Runnable {
            val anim = SpringAnimation(view, prop)
            anim.setSpring(
                SpringForce(to).setDampingRatio(damping).setStiffness(stiffness * speed * speed)
            )
            map[prop] = anim
            anim.start()
        }
        if (delayMs > 0L) view.postDelayed(start, (delayMs / speed).toLong()) else start.run()
    }

    fun scale(view: View, to: Float, damping: Float, stiffness: Float, from: Float? = null, delayMs: Long = 0L) {
        spring(view, DynamicAnimation.SCALE_X, to, damping, stiffness, from, delayMs)
        spring(view, DynamicAnimation.SCALE_Y, to, damping, stiffness, from, delayMs)
    }

    /** Controls bloom in: bars glide in, the buttons pop up with a bounce, centre first. */
    fun controlsIn(top: View, center: List<View>, bottom: View, d: Float) {
        spring(top, DynamicAnimation.TRANSLATION_Y, 0f, 0.7f, 320f, from = -22f * d)
        spring(top, DynamicAnimation.ALPHA, 1f, 1f, 300f, from = 0f)
        center.forEachIndexed { i, v ->
            val delay = if (i == 0) 0L else 55L
            scale(v, 1f, 0.5f, 420f, from = 0.55f, delayMs = delay)
            spring(v, DynamicAnimation.ALPHA, 1f, 1f, 350f, from = 0f, delayMs = delay)
        }
        spring(bottom, DynamicAnimation.TRANSLATION_Y, 0f, 0.7f, 320f, from = 26f * d)
        spring(bottom, DynamicAnimation.ALPHA, 1f, 1f, 300f, from = 0f)
    }

    fun controlsOut(top: View, center: List<View>, bottom: View, d: Float) {
        spring(top, DynamicAnimation.TRANSLATION_Y, -14f * d, 1f, 500f)
        spring(top, DynamicAnimation.ALPHA, 0f, 1f, 500f)
        center.forEach { v ->
            scale(v, 0.86f, 1f, 500f)
            spring(v, DynamicAnimation.ALPHA, 0f, 1f, 500f)
        }
        spring(bottom, DynamicAnimation.TRANSLATION_Y, 16f * d, 1f, 500f)
        spring(bottom, DynamicAnimation.ALPHA, 0f, 1f, 500f)
    }
}
