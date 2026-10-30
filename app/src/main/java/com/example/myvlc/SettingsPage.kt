package com.example.myvlc

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import com.google.android.material.materialswitch.MaterialSwitch

/** Which swipe / tap gestures are turned on. Saved in the app preferences. */
object Gestures {
    var brightness = true
    var volume = true
    var seek = true
    var doubleTap = true
    private var prefs: SharedPreferences? = null

    fun init(p: SharedPreferences) {
        prefs = p
        brightness = p.getBoolean("g_brightness", true)
        volume = p.getBoolean("g_volume", true)
        seek = p.getBoolean("g_seek", true)
        doubleTap = p.getBoolean("g_double", true)
    }

    fun save(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }
}

/** The Settings page: rounded groups that glide in, switches for the gestures. */
class SettingsPage(
    private val ctx: Context,
    private val color: (Int, Int) -> Int,
    private val onClose: () -> Unit,
    private val onTheme: () -> Unit,
    private val onGlass: () -> Unit,
    private val onAbout: () -> Unit
) {
    private val d = ctx.resources.displayMetrics.density
    val root: View

    private fun px(v: Int) = (v * d).toInt()

    init {
        val surface = color(com.google.android.material.R.attr.colorSurface, Color.BLACK)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val variant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val primary = color(androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val card = color(com.google.android.material.R.attr.colorSurfaceVariant, Color.DKGRAY)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val page = LinearLayout(ctx)
        page.orientation = LinearLayout.VERTICAL
        page.setBackgroundColor(surface)
        page.isClickable = true
        ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // header: back button + title
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(px(12), px(10), px(12), px(6))
        val back = ImageButton(ctx)
        back.setImageResource(R.drawable.ic_arrow_back)
        back.setColorFilter(onSurface)
        back.setBackgroundColor(Color.TRANSPARENT)
        back.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        back.setPadding(px(11), px(11), px(11), px(11))
        back.setOnClickListener { onClose() }
        pressFeel(back)
        header.addView(back, LinearLayout.LayoutParams(px(44), px(44)))
        val title = TextView(ctx)
        title.text = "Settings"
        title.textSize = 28f
        title.setTextColor(onSurface)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        val tlp = LinearLayout.LayoutParams(wrap, wrap)
        tlp.marginStart = px(8)
        header.addView(title, tlp)
        page.addView(header, LinearLayout.LayoutParams(match, wrap))

        val content = LinearLayout(ctx)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(16), px(4), px(16), px(28))
        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content, ViewGroup.LayoutParams(match, wrap))
        page.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        val gliding = ArrayList<View>()

        fun section(text: String) {
            val t = TextView(ctx)
            t.text = text
            t.textSize = 13f
            t.setTextColor(primary)
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            t.setPadding(px(10), px(18), px(10), px(8))
            content.addView(t, LinearLayout.LayoutParams(match, wrap))
            gliding.add(t)
        }

        fun group(): LinearLayout {
            val g = LinearLayout(ctx)
            g.orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.setColor(card)
            bg.cornerRadius = 26f * d
            g.background = bg
            g.setPadding(0, px(4), 0, px(4))
            content.addView(g, LinearLayout.LayoutParams(match, wrap))
            gliding.add(g)
            return g
        }

        fun row(g: LinearLayout, titleText: String, sub: String, trailing: View?, onClick: () -> Unit) {
            if (g.childCount > 0) {
                val line = View(ctx)
                line.setBackgroundColor((variant and 0x00FFFFFF) or 0x22000000)
                val llp = LinearLayout.LayoutParams(match, 1)
                llp.marginStart = px(20)
                llp.marginEnd = px(20)
                g.addView(line, llp)
            }
            val r = LinearLayout(ctx)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(px(20), px(14), px(16), px(14))
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            val t = TextView(ctx)
            t.text = titleText
            t.textSize = 16f
            t.setTextColor(onSurface)
            val s = TextView(ctx)
            s.text = sub
            s.textSize = 13f
            s.setTextColor(variant)
            col.addView(t)
            col.addView(s)
            r.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
            if (trailing != null) r.addView(trailing)
            r.setOnClickListener { onClick() }
            pressFeel(r, 0.97f)
            g.addView(r, LinearLayout.LayoutParams(match, wrap))
        }

        fun switchRow(g: LinearLayout, titleText: String, sub: String, key: String, value: Boolean, set: (Boolean) -> Unit) {
            val sw = MaterialSwitch(ctx)
            sw.isChecked = value
            sw.setOnCheckedChangeListener { v, checked ->
                set(checked)
                Gestures.save(key, checked)
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
            row(g, titleText, sub, sw) { sw.isChecked = !sw.isChecked }
        }

        section("GESTURES")
        val gestures = group()
        switchRow(gestures, "Brightness swipe", "Swipe up or down on the left side of the video", "g_brightness", Gestures.brightness) { Gestures.brightness = it }
        switchRow(gestures, "Volume swipe", "Swipe up or down on the right side of the video", "g_volume", Gestures.volume) { Gestures.volume = it }
        switchRow(gestures, "Seek swipe", "Swipe left or right to move through the video", "g_seek", Gestures.seek) { Gestures.seek = it }
        switchRow(gestures, "Double-tap to skip", "Double-tap the left or right side to skip 10 seconds", "g_double", Gestures.doubleTap) { Gestures.doubleTap = it }

        section("LOOK")
        val look = group()
        row(look, "Theme", "Colours of the app", null) { onTheme() }
        row(look, "Glass & blur", "Tune the liquid glass of the player", null) { onGlass() }

        section("ABOUT")
        val about = group()
        row(about, "About Prism", "A frosted-glass video player built on libVLC", null) { onAbout() }

        // everything glides up one after the other
        gliding.forEachIndexed { i, v ->
            val delay = 120L + i * 45L
            v.alpha = 0f
            v.translationY = 28f * d
            Fluid.spring(v, DynamicAnimation.ALPHA, 1f, 1f, 300f, from = 0f, delayMs = delay)
            Fluid.spring(v, DynamicAnimation.TRANSLATION_Y, 0f, 0.75f, 320f, from = 28f * d, delayMs = delay)
        }
        root = page
    }

    private fun pressFeel(v: View, down: Float = 0.86f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> Fluid.scale(view, down, 0.6f, 800f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Fluid.scale(view, 1f, 0.4f, 300f)
            }
            false
        }
    }
}
