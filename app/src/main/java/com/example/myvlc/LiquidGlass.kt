package com.example.myvlc

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.annotation.RequiresApi
import kotlin.math.min

/**
 * Real liquid glass (Android 13+, AGSL).
 *
 * The lens bends whatever is behind it near the rim (refraction), splits the colour
 * channels slightly (dispersion), magnifies the middle a touch, and adds a lit rim
 * plus a soft inner glow. Older phones fall back to the plain frosted blur.
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
    float bend = pow(1.0 - t, 2.4);
    float2 centre = origin + hs;
    float zoom = 1.0 - mag * smoothstep(0.0, 1.0, t);
    float2 base = centre + (coord - centre) * zoom;
    float2 off = -n * bend * refraction;

    float3 col = float3(
        scene(base + off * (1.0 + dispersion)).r,
        scene(base + off).g,
        scene(base + off * (1.0 - dispersion)).b
    );

    col = mix(col, tint.rgb, tint.a);

    float2 L = normalize(float2(-0.55, -0.83));
    float lit = max(dot(n, L), 0.0);
    float back = max(dot(n, -L), 0.0);
    float rim = 1.0 - smoothstep(0.0, 2.4 * dens, depth);
    float spec = rim * (0.18 + 0.85 * pow(lit, 2.0) + 0.40 * pow(back, 2.0));
    float glow = (1.0 - smoothstep(0.0, edge, depth)) * 0.06 * (0.6 + lit);
    col += float3(spec + glow + press * 0.07);

    float aa = clamp(depth + 0.5, 0.0, 1.0);
    return half4(half3(col * aa), half(aa));
}
"""

    /** Glass over a single ImageView that already shows the blurred video snapshot. */
    private const val VIEW_SRC = "uniform shader content;\n" + UNIFORMS +
        "float3 scene(float2 c) { return float3(content.eval(clamp(c, lo, hi)).rgb); }\n" + MAIN

    /** Glass thumb: refracts the video AND the slider track drawn underneath it. */
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

    /**
     * Turn a blur ImageView into a glass lens.
     * @param cornerDp corner radius in dp, or null for a full pill / circle
     * @param strength how hard the rim bends the background (0.28 for buttons, ~0.06 for big panels)
     */
    @RequiresApi(33)
    fun attach(view: View, cornerDp: Float? = null, strength: Float = 0.28f) {
        val dens = view.resources.displayMetrics.density
        val shader = RuntimeShader(VIEW_SRC)
        var lastW = -1
        var lastH = -1

        fun rebuild() {
            val w = view.width
            val h = view.height
            if (w < 2 || h < 2) return
            if (w == lastW && h == lastH) return
            lastW = w
            lastH = h
            val m = min(w, h).toFloat()
            val r = if (cornerDp != null) cornerDp * dens else m / 2f
            configure(
                shader,
                w.toFloat(), h.toFloat(), 0f, 0f,
                r,
                min(m * strength, 26f * dens),
                min(m * 0.36f, 34f * dens),
                0.05f, 0.06f, dens, 0f,
                true
            )
            val blur = RenderEffect.createBlurEffect(5f, 5f, Shader.TileMode.CLAMP)
            val glass = RenderEffect.createRuntimeShaderEffect(shader, "content")
            view.setRenderEffect(RenderEffect.createChainEffect(glass, blur))
        }

        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rebuild() }
        rebuild()
    }

    /** iOS-style swell: the glass bulges while a finger is on it, then springs back. */
    fun swell(trigger: View, target: View = trigger.parent as View) {
        trigger.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    target.animate().scaleX(1.14f).scaleY(1.14f).setDuration(140)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(OvershootInterpolator(2.4f)).start()
            }
            false
        }
    }
}
