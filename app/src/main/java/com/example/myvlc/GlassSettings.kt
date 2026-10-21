package com.example.myvlc

import android.content.SharedPreferences

/** User-tunable look of the liquid glass, saved in the app preferences. */
object GlassSettings {
    var liquid = true          // real refraction (Android 13+); off = plain frosted blur
    var blur = 8f              // px of blur behind the glass
    var refraction = 1f        // multiplier on how hard the rim bends the video
    var tint = 0.16f           // darkness of the glass (keeps white icons readable)
    var dispersion = 0.07f     // colour split at the rim
    var bandHeightDp = 84f     // height of the frosted band at the bottom of the player
    var bandBlur = 24f         // blur strength of that band
    var animSpeed = 1f         // 0.5 = lazy, 2 = snappy

    private var prefs: SharedPreferences? = null

    fun init(p: SharedPreferences) {
        prefs = p
        liquid = p.getBoolean("g_liquid", true)
        blur = p.getFloat("g_blur", 8f)
        refraction = p.getFloat("g_refraction", 1f)
        tint = p.getFloat("g_tint", 0.16f)
        dispersion = p.getFloat("g_dispersion", 0.07f)
        bandHeightDp = p.getFloat("g_band_h", 84f)
        bandBlur = p.getFloat("g_band_blur", 24f)
        animSpeed = p.getFloat("g_anim", 1f)
    }

    fun save() {
        prefs?.edit()
            ?.putBoolean("g_liquid", liquid)
            ?.putFloat("g_blur", blur)
            ?.putFloat("g_refraction", refraction)
            ?.putFloat("g_tint", tint)
            ?.putFloat("g_dispersion", dispersion)
            ?.putFloat("g_band_h", bandHeightDp)
            ?.putFloat("g_band_blur", bandBlur)
            ?.putFloat("g_anim", animSpeed)
            ?.apply()
    }

    fun reset() {
        liquid = true
        blur = 8f
        refraction = 1f
        tint = 0.16f
        dispersion = 0.07f
        bandHeightDp = 84f
        bandBlur = 24f
        animSpeed = 1f
        save()
    }
}
