import os, sys

ROOT = os.path.expanduser("~/PrismVLC/app/src/main/")
JAVA = ROOT + "java/com/example/myvlc/"
edits = {}

def add(path, old, new):
    edits.setdefault(path, []).append((old, new))

MAIN = JAVA + "MainActivity.kt"
SEEK = JAVA + "PrismSeekBar.kt"
XML = ROOT + "res/layout/activity_main.xml"

# ---- 1. only the progress bar: no glass capsule behind it
add(SEEK, r'''        // 1. glass capsule behind the bar
        if (haveGlass) {
            tb!!.eraseColor(Color.TRANSPARENT)
            drawPill(canvas, tb, bd!!, cy)
        } else {
            val h = 40f * d
            rect.set(2f * d, cy - h / 2f, width - 2f * d, cy + h / 2f)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fallbackFill)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fallbackStroke)
        }
''', r'''        // 1. no glass capsule any more: only the slim progress bar
''')

# ---- 4. bigger play / pause icon (the morph animation stays)
add(XML, r'''                    android:contentDescription="Play or pause"
                    android:padding="22dp"''', r'''                    android:contentDescription="Play or pause"
                    android:padding="15dp"''')

# ---- 2. glass no longer cut off when pressed: let every parent draw outside its edges
add(MAIN, r'''        LiquidGlass.swell(b.nextPill, b.nextPill)
''', r'''        LiquidGlass.swell(b.nextPill, b.nextPill)
        unclipParents(
            listOf(
                b.prevBtn, b.playPause, b.nextBtn, b.backBtn, b.lockBtn, b.nextPill,
                b.ccBtn, b.audioBtn, b.castBtn, b.speedBtn, b.moreBtn, b.rotateBtn
            )
        )
        // the option buttons bounce when you press them
        for (btn in listOf<View>(b.ccBtn, b.audioBtn, b.castBtn, b.speedBtn, b.moreBtn, b.rotateBtn)) {
            pressBounce(btn)
        }
''')

# ---- 3. gestures on/off
add(MAIN, r'''    private var settingsOpen = false
''', r'''    private var settingsOpen = false
    private var settingsPage: View? = null
''')
add(MAIN, r'''        GlassSettings.init(prefs)
''', r'''        GlassSettings.init(prefs)
        Gestures.init(prefs)
''')
add(MAIN, r'''                if (gMode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    gMode = if (abs(dy) > abs(dx)) {
                        if (gDownX < b.touchLayer.width / 2f) 1 else 2
                    } else 3
                }''', r'''                if (gMode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    gMode = if (abs(dy) > abs(dx)) {
                        if (gDownX < b.touchLayer.width / 2f) 1 else 2
                    } else 3
                    // a gesture that is switched off in Settings is ignored
                    if ((gMode == 1 && !Gestures.brightness) ||
                        (gMode == 2 && !Gestures.volume) ||
                        (gMode == 3 && !Gestures.seek)
                    ) gMode = -1
                }''')
add(MAIN, r'''                if (locked || !hasMedia) return true
                val back = e.x < b.touchLayer.width / 2f''', r'''                if (locked || !hasMedia || !Gestures.doubleTap) return true
                val back = e.x < b.touchLayer.width / 2f''')
add(MAIN, r'''            if (locked) showUnlockTemporarily()
            else if (panelVisible) hidePanel()''', r'''            if (settingsPage != null) closeSettingsPage()
            else if (locked) showUnlockTemporarily()
            else if (panelVisible) hidePanel()''')

# ---- Settings entry in the library menu and in the player's "more" panel
add(MAIN, r'''            "Glass & blur settings",
            "About Prism"
        )''', r'''            "Glass & blur settings",
            "Settings",
            "About Prism"
        )''')
add(MAIN, r'''                    4 -> showGlassSettings()
                    else -> MaterialAlertDialogBuilder(this)''', r'''                    4 -> showGlassSettings()
                    5 -> openSettingsPage()
                    else -> MaterialAlertDialogBuilder(this)''')
add(MAIN, r'''"Picture-in-picture", "Lock screen", "Video info", "Glass & blur settings", "Share"''',
    r'''"Picture-in-picture", "Lock screen", "Video info", "Glass & blur settings", "Share", "Settings"''')
add(MAIN, r'''                7 -> showGlassSettings()
                else -> {''', r'''                7 -> showGlassSettings()
                9 -> openSettingsPage()
                else -> {''')

# ---- 5. the options panel (subtitles, audio, speed, more) now animates in and out
add(MAIN, r'''        val heightDp = minOf(labels.size * 48 + 16, 320)
        val lp = b.optionsPanel.layoutParams
        lp.height = (heightDp * density).toInt()
        b.optionsPanel.layoutParams = lp
        b.optionsPanel.visibility = View.VISIBLE
        panelVisible = true
        handler.removeCallbacks(hideRunnable)
    }

    private fun hidePanel() {
        if (!panelVisible) return
        b.optionsPanel.visibility = View.GONE
        panelVisible = false
        scheduleHide()
    }''', r'''        val heightDp = minOf(labels.size * 48 + 16, 320)
        val lp = b.optionsPanel.layoutParams
        lp.height = (heightDp * density).toInt()
        b.optionsPanel.layoutParams = lp
        val panel = b.optionsPanel
        panel.pivotX = 0f
        panel.pivotY = lp.height.toFloat()
        panel.visibility = View.VISIBLE
        panelVisible = true
        handler.removeCallbacks(hideRunnable)
        // the panel springs out of its button, then the rows glide in one by one
        Fluid.scale(panel, 1f, 0.62f, 420f, from = 0.82f)
        Fluid.spring(panel, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f, 1f, 500f, from = 0f)
        Fluid.spring(
            panel, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y,
            0f, 0.7f, 380f, from = 14f * density
        )
        for (i in 0 until b.optionsList.childCount) {
            val row = b.optionsList.getChildAt(i)
            val delay = 40L + minOf(i, 8) * 22L
            Fluid.spring(row, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f, 1f, 400f, from = 0f, delayMs = delay)
            Fluid.spring(
                row, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y,
                0f, 0.8f, 400f, from = 10f * density, delayMs = delay
            )
        }
    }

    private fun hidePanel() {
        if (!panelVisible) return
        panelVisible = false
        val panel = b.optionsPanel
        Fluid.scale(panel, 0.9f, 1f, 600f)
        Fluid.spring(panel, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 0f, 1f, 600f)
        panel.postDelayed({
            if (!panelVisible) panel.visibility = View.GONE
        }, 200)
        scheduleHide()
    }''')

# ---- new helpers: settings page, unclip, bounce, about
add(MAIN, r'''    // ---------- Cast ----------
''', r'''    // ---------- Settings page ----------

    private fun openSettingsPage() {
        if (settingsPage != null) return
        hidePanel()
        val page = SettingsPage(
            this,
            { attr, fallback -> themeColor(attr, fallback) },
            { closeSettingsPage() },
            { showThemeDialog() },
            { showGlassSettings() },
            { showAbout() }
        ).root
        settingsPage = page
        b.root.addView(
            page,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        page.translationX = resources.displayMetrics.widthPixels.toFloat()
        Fluid.spring(page, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, 0f, 0.9f, 300f)
        backCallback.isEnabled = true
        handler.removeCallbacks(hideRunnable)
    }

    private fun closeSettingsPage() {
        val p = settingsPage ?: return
        settingsPage = null
        Fluid.spring(
            p, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
            resources.displayMetrics.widthPixels.toFloat(), 1f, 420f
        )
        p.postDelayed({ (p.parent as? ViewGroup)?.removeView(p) }, 320)
        backCallback.isEnabled = !inLibrary || searchVisible || currentSeries != null ||
            currentPlaylist != null || currentFolder != null || panelVisible
        scheduleHide()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Prism")
            .setMessage("A frosted-glass video player built on libVLC.\n\nUnofficial, not affiliated with VideoLAN.")
            .setPositiveButton("OK", null)
            .show()
    }

    /** Lets a growing (pressed) glass button draw outside the box of its parents. */
    private fun unclipParents(views: List<View>) {
        for (v in views) {
            var p = v.parent
            while (p is ViewGroup) {
                p.clipChildren = false
                p.clipToPadding = false
                if (p === b.controls) break
                p = p.parent
            }
        }
    }

    private fun pressBounce(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> Fluid.scale(view, 0.82f, 0.55f, 800f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Fluid.scale(view, 1f, 0.35f, 300f)
            }
            false
        }
    }

    // ---------- Cast ----------
''')

new_contents = {}
bad = False
for path, lst in edits.items():
    if not os.path.exists(path):
        print("MISSING FILE:", path)
        bad = True
        continue
    text = open(path, encoding="utf-8").read()
    for old, new in lst:
        n = text.count(old)
        if n != 1:
            print("PROBLEM in", os.path.basename(path), "- found", n, "times:", old[:70].replace("\n", " | "))
            bad = True
        else:
            text = text.replace(old, new)
    new_contents[path] = text

if bad:
    print("\nNothing was changed. Send me the lines above.")
    sys.exit(1)

for path, text in new_contents.items():
    open(path, "w", encoding="utf-8").write(text)
    print("patched", os.path.basename(path))
print("\nDone. Now: cd ~/PrismVLC && git add -A && git commit -m v3 && git push")
