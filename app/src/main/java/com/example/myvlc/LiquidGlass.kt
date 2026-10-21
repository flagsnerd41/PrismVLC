package com.example.myvlc

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.RequiresApi
import androidx.dynamicanimation.animation.DynamicAnimation
import java.lang.ref.WeakReference
import kotlin.math.min

/**
 * Real liquid glass (Android 13+, AGSL).
 *
 * The lens bends whatever is behind it near the rim (refraction), splits the colour
 * channels slightly (dispersion), lifts the saturation, adds a lit rim with a soft
 * shadow on the far side. Pressing a button swells it and bends the light harder.
 * Everything is tunable from GlassSettings. Older phones fall back to a frosted blur.
 */
object LiquidGlass {

    private const val UNIFORMS = """
uniform float2 size;
uniform float2 origin;
uniform float2 lo;
uniform float2 hi;
uniform float radius;
uniform float refraction;
uniform float dispersion;
uniform float edge;
uniform float mag;
uniform float dens;
uniform float press;
uniform float4 tint;
"""

    private const val MAIN = """
half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - origin - hs;
    float r = min(radius, min(hs.x, hs.y));
    float2 q = abs(p) - (hs - float2(r));
    float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
    float depth = -d;

    float2 n;
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(q);
    } else if (q.x > q.y) {
        n = float2(1.0, 0.0);
    } else {
        n = float2(0.0, 1.0);
    }
    n *= sign(p);

    float t = clamp(depth / edge, 0.0, 1.0);
    float s = 1.0 - t;
    float bend = pow(s, 1.8) * (0.55 + 0.45 * s);
    float2 centre = origin + hs;
    float zoom = 1.0 - mag * smoothstep(0.0, 1.0, t);
    float2 base = centre + (coord - centre) * zoom;
    float2 off = -n * bend * refraction;

    float3 col = float3(
        scene(base + off * (1.0 + dispersion)).r,
        scene(base + off).g,
        scene(base + off * (1.0 - dispersion)).b
    );

    float luma = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(luma), col, 1.22);
    col = mix(col, tint.rgb, tint.a);

    float2 L = normalize(float2(-0.55, -0.83));
    float lit = max(dot(n, L), 0.0);
    float back = max(dot(n, -L), 0.0);
    col *= 1.0 - 0.14 * back * s;

    float rim = 1.0 - smoothstep(0.0, 2.4 * dens, depth);
    float spec = rim * (0.18 + 0.85 * pow(lit, 2.0) + 0.40 * pow(back, 2.0));
    float glow = (1.0 - smoothstep(0.0, edge, depth)) * 0.06 * (0.6 + lit);
    col += float3(spec + glow + press * 0.07);

    float aa = clamp(depth + 0.5, 0.0, 1.0);
    return half4(half3(col * aa), half(aa));
}
"""

    /** Glass over a single ImageView that already shows the video snapshot. */
    private const val VIEW_SRC = "uniform shader content;\n" + UNIFORMS +
        "float3 scene(float2 c) { return float3(content.eval(clamp(c, lo, hi)).rgb); }\n" + MAIN

    /** Glass that refracts the video AND the slider track drawn underneath it. */
    private const val SEEK_SRC = "uniform shader backdrop;\nuniform shader track;\n" + UNIFORMS +
        """
float3 scene(float2 c) {
    half4 b = backdrop.eval(c);
    half4 t = track.eval(c);
    return float3(b.rgb) * (1.0 - float(t.a)) + float3(t.rgb);
}
""" + MAIN

    @RequiresApi(33)
    fun newSeekShader(): RuntimeShader = RuntimeShader(SEEK_SRC)

    @RequiresApi(33)
    fun configure(
        s: RuntimeShader,
        w: Float, h: Float,
        ox: Float, oy: Float,
        radius: Float,
        refraction: Float,
        edge: Float,
        mag: Float,
        dispersion: Float,
        dens: Float,
        press: Float,
        bounded: Boolean,
        tintRgb: Float = 0f,
        tintA: Float = 0.16f
    ) {
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("origin", ox, oy)
        if (bounded) {
            s.setFloatUniform("lo", ox + 0.5f, oy + 0.5f)
            s.setFloatUniform("hi", ox + w - 0.5f, oy + h - 0.5f)
        } else {
            s.setFloatUniform("lo", -1e6f, -1e6f)
            s.setFloatUniform("hi", 1e6f, 1e6f)
        }
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("refraction", refraction)
        s.setFloatUniform("edge", edge)
        s.setFloatUniform("mag", mag)
        s.setFloatUniform("dispersion", dispersion)
        s.setFloatUniform("dens", dens)
        s.setFloatUniform("press", press)
        s.setFloatUniform("tint", tintRgb, tintRgb, tintRgb, tintA)
    }

    /** One glass lens on one ImageView. */
    @RequiresApi(33)
    private class Entry(val view: View, val cornerDp: Float?, val strength: Float) {
        val shader = RuntimeShader(VIEW_SRC)
        var press = 0f
        var animator: ValueAnimator? = null
        private var lastW = -1
        private var lastH = -1

        fun rebuild(force: Boolean) {
            val w = view.width
            val h = view.height
            if (w < 2 || h < 2) return
            if (!force && w == lastW && h == lastH) return
            lastW = w
            lastH = h
            val dens = view.resources.displayMetrics.density
            val blurPx = maxOf(GlassSettings.blur, 0.5f)
            val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            if (!GlassSettings.liquid) {
                view.setRenderEffect(blur)
                return
            }
            val m = min(w, h).toFloat()
            val r = if (cornerDp != null) cornerDp * dens else m / 2f
            val k = GlassSettings.refraction * (1f + 0.45f * press)
            configure(
                shader,
                w.toFloat(), h.toFloat(), 0f, 0f,
                r,
                min(m * strength * k, 30f * dens),
                min(m * 0.36f, 34f * dens),
                0.05f + 0.05f * press,
                GlassSettings.dispersion,
                dens,
                press,
                true,
                0f, GlassSettings.tint
            )
            val glass = RenderEffect.createRuntimeShaderEffect(shader, "content")
            view.setRenderEffect(RenderEffect.createChainEffect(glass, blur))
        }
    }

    private val entries = ArrayList<WeakReference<Entry>>()

    /**
     * Turn a blur ImageView into a glass lens.
     * @param cornerDp corner radius in dp, or null for a full pill / circle
     * @param strength how hard the rim bends the background (0.28 for buttons, ~0.06 for big panels)
     */
    @RequiresApi(33)
    fun attach(view: View, cornerDp: Float? = null, strength: Float = 0.28f) {
        val e = Entry(view, cornerDp, strength)
        entries.add(WeakReference(e))
        // the layout listener keeps the entry alive for as long as the view lives
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> e.rebuild(false) }
        e.rebuild(false)
    }

    /** Re-read GlassSettings and redraw every lens. */
    @RequiresApi(33)
    fun refreshAll() {
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next().get()
            if (e == null) it.remove() else e.rebuild(true)
        }
    }

    /** Smoothly push a lens into / out of its pressed state. */
    @RequiresApi(33)
    fun pressTo(view: View?, target: Float) {
        if (view == null) return
        var found: Entry? = null
        for (ref in entries) {
            val e = ref.get()
            if (e != null && e.view === view) {
                found = e
                break
            }
        }
        val e = found ?: return
        e.animator?.cancel()
        val a = ValueAnimator.ofFloat(e.press, target)
        a.duration = 240
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener {
            e.press = it.animatedValue as Float
            e.rebuild(true)
        }
        e.animator = a
        a.start()
    }

    /** iOS-style swell: the glass bulges and bends light harder while pressed, then springs back. */
    fun swell(trigger: View, target: View = trigger.parent as View) {
        trigger.setOnTouchListener { _, e ->
            val glassView = (target as? android.view.ViewGroup)?.getChildAt(0)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Fluid.scale(target, 1.14f, 0.45f, 700f)
                    if (Build.VERSION.SDK_INT >= 33) pressTo(glassView, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Fluid.scale(target, 1f, 0.32f, 260f)
                    if (Build.VERSION.SDK_INT >= 33) pressTo(glassView, 0f)
                }
            }
            false
        }
    }
}
