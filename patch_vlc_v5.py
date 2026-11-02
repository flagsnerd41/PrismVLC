#!/usr/bin/env python3
"""PrismVLC home + player redesign patch.
Run: python3 ~/PrismVLC/patch_vlc_v5.py
Then: git add -A && git commit -m "Home + player redesign" && git push
Nothing is written unless every edit matches."""
import re, sys, pathlib

import os
ROOT = pathlib.Path(os.path.expanduser("~/PrismVLC"))
MAIN = ROOT / "app/src/main/java/com/example/myvlc/MainActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
for p in (MAIN, LAYOUT):
    if not p.exists():
        sys.exit(f"Missing {p} - clone/extract the repo to ~/PrismVLC first")

kt = MAIN.read_text(encoding="utf-8")
xml = LAYOUT.read_text(encoding="utf-8")


def rep(text, old, new, label):
    if text.count(old) != 1:
        sys.exit(f"FAILED ({label}): expected 1 match, found {text.count(old)}")
    return text.replace(old, new)


def rex(text, pattern, new, label, flags=re.DOTALL):
    found = re.findall(pattern, text, flags)
    if len(found) != 1:
        sys.exit(f"FAILED ({label}): expected 1 match, found {len(found)}")
    return re.sub(pattern, lambda m: new(m) if callable(new) else new, text, count=1, flags=flags)


# ---------------- activity_main.xml ----------------

# 1. cast button leaves the bottom pill (moves to the top pill)
xml = rex(xml, r'\s*<ImageButton\s+android:id="@\+id/castBtn".*?/>', "", "remove old castBtn")

# 2. top glass pill (PiP, cast, share) next to the close button
TOP_PILL = '''<FrameLayout
                android:layout_width="wrap_content"
                android:layout_height="46dp"
                android:layout_marginStart="8dp">

                <ImageView
                    android:id="@+id/blurTopPill"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:contentDescription="@null"
                    android:maxWidth="1dp"
                    android:maxHeight="1dp"
                    android:scaleType="matrix" />

                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:paddingHorizontal="6dp">

                    <ImageButton
                        android:id="@+id/pipBtn"
                        android:layout_width="38dp"
                        android:layout_height="42dp"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:contentDescription="Picture-in-picture"
                        android:padding="9dp"
                        android:scaleType="centerInside"
                        android:src="@drawable/ic_pip"
                        app:tint="@android:color/white" />

                    <ImageButton
                        android:id="@+id/castBtn"
                        android:layout_width="38dp"
                        android:layout_height="42dp"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:contentDescription="Cast"
                        android:padding="9dp"
                        android:scaleType="centerInside"
                        android:src="@drawable/ic_cast"
                        app:tint="@android:color/white" />

                    <ImageButton
                        android:id="@+id/shareBtn"
                        android:layout_width="38dp"
                        android:layout_height="42dp"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:contentDescription="Share"
                        android:padding="9dp"
                        android:scaleType="centerInside"
                        android:src="@drawable/ic_share"
                        app:tint="@android:color/white" />
                </LinearLayout>
            </FrameLayout>

            '''
xml = rex(
    xml,
    r'<View\s+android:layout_width="0dp"\s+android:layout_height="1dp"\s+android:layout_weight="1" />',
    lambda m: TOP_PILL + m.group(0),
    "top pill",
)

# 3. lock button hidden (still in the More menu)
xml = rex(
    xml,
    r'(android:id="@\+id/lockBtn".*?)(app:tint="@android:color/white" />)',
    lambda m: m.group(1) + 'android:visibility="gone"\n                ' + m.group(2),
    "lock hidden",
)

# 4. volume pill: taller, width from dimens (wider in landscape)
xml = rex(
    xml,
    r'android:layout_width="156dp"\s+android:layout_height="40dp"',
    'android:layout_width="@dimen/vol_pill_width"\n                android:layout_height="46dp"',
    "volume pill",
)

# 5. Info chip (landscape) in front of the Next pill
INFO_CHIP = '''<LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="start|bottom"
                    android:gravity="center_vertical"
                    android:orientation="horizontal">

                    <FrameLayout
                        android:id="@+id/infoChip"
                        android:layout_width="64dp"
                        android:layout_height="44dp"
                        android:layout_marginEnd="8dp"
                        android:clickable="true"
                        android:focusable="true"
                        android:visibility="gone">

                        <ImageView
                            android:id="@+id/blurInfo"
                            android:layout_width="match_parent"
                            android:layout_height="match_parent"
                            android:contentDescription="@null"
                            android:maxWidth="1dp"
                            android:maxHeight="1dp"
                            android:scaleType="matrix" />

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="match_parent"
                            android:gravity="center"
                            android:text="Info"
                            android:textColor="@android:color/white"
                            android:textSize="13sp"
                            android:textStyle="bold" />
                    </FrameLayout>

                    <FrameLayout
                    android:id="@+id/nextPill"
                    android:layout_width="wrap_content"
                    android:layout_height="52dp"'''
xml = rex(
    xml,
    r'<FrameLayout\s+android:id="@\+id/nextPill"\s+android:layout_width="wrap_content"\s+android:layout_height="52dp"\s+android:layout_gravity="start"',
    INFO_CHIP,
    "info chip",
)
xml = rex(
    xml,
    r'(</LinearLayout>\s*</FrameLayout>)(\s*<FrameLayout\s+android:layout_width="wrap_content"\s+android:layout_height="52dp"\s+android:layout_gravity="end">)',
    lambda m: m.group(1) + "\n                </LinearLayout>" + m.group(2),
    "close chip row",
)

# 6. header: greeting under the title
HEADER = '''<LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="12dp"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/libTitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:ellipsize="end"
                    android:maxLines="1"
                    android:text="Prism"
                    android:textColor="?attr/colorOnSurface"
                    android:textSize="30sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/libGreeting"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:ellipsize="end"
                    android:maxLines="1"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:textSize="15sp" />
            </LinearLayout>'''
xml = rex(xml, r'<TextView\s+android:id="@\+id/libTitle".*?/>', HEADER, "header")

# 7. floating glass bottom bar
NAV = '''<com.google.android.material.bottomnavigation.BottomNavigationView
            android:id="@+id/bottomNav"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:layout_marginTop="4dp"
            android:layout_marginEnd="16dp"
            android:layout_marginBottom="10dp"
            android:background="@drawable/nav_glass_bg"
            app:backgroundTint="@null"
            app:elevation="0dp"
            app:labelVisibilityMode="labeled"
            app:menu="@menu/nav_menu" />'''
xml = rex(
    xml,
    r'<com\.google\.android\.material\.bottomnavigation\.BottomNavigationView.*?/>',
    NAV,
    "bottom nav",
)

# ---------------- MainActivity.kt ----------------

kt = rep(
    kt,
    "b.blurBack, b.blurLock, b.blurBottom",
    "b.blurBack, b.blurLock, b.blurBottom, b.blurTopPill, b.blurInfo",
    "blur views",
)

kt = rep(
    kt,
    "        b.libLogo.visibility = if (detail) View.GONE else View.VISIBLE\n",
    "        b.libLogo.visibility = if (detail) View.GONE else View.VISIBLE\n"
    "        b.libGreeting.visibility = if (detail) View.GONE else View.VISIBLE\n"
    "        if (!detail) b.libGreeting.text = greetingText()\n",
    "greeting header",
)

kt = rep(
    kt,
    "    private fun toggleSearch() {",
    '''    private fun greetingText(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val name = prefs.getString("user_name", "Ilyes") ?: "Ilyes"
        return when {
            hour < 5 -> "Good Night, $name \\uD83C\\uDF19"
            hour < 12 -> "Good Morning, $name \\u2600\\uFE0F"
            hour < 18 -> "Good Afternoon, $name \\uD83C\\uDF24\\uFE0F"
            else -> "Good Evening, $name \\uD83C\\uDF19"
        }
    }

    /** The Info chip only shows in landscape (portrait is too narrow for it). */
    private fun updateChrome(cfg: Configuration = resources.configuration) {
        b.infoChip.visibility =
            if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) View.VISIBLE else View.GONE
    }

    private fun toggleSearch() {''',
    "greeting + chrome functions",
)

kt = rep(
    kt,
    "        b.rotateBtn.setOnClickListener { tick(it); toggleOrientation() }\n",
    '''        b.rotateBtn.setOnClickListener { tick(it); toggleOrientation() }
        b.pipBtn.setOnClickListener { tick(it); enterPip() }
        b.shareBtn.setOnClickListener {
            tick(it)
            val item = currentItem()
            if (item != null) shareVideo(item) else toast("Nothing to share")
        }
        b.infoChip.setOnClickListener {
            tick(it)
            val item = currentItem()
            if (item != null) showInfo(item) else toast("No info for this video")
        }
        updateChrome()
''',
    "new button listeners",
)

kt = rep(
    kt,
    "listOf<View>(b.ccBtn, b.audioBtn, b.castBtn, b.speedBtn, b.moreBtn, b.rotateBtn)",
    "listOf<View>(b.ccBtn, b.audioBtn, b.castBtn, b.speedBtn, b.moreBtn, b.rotateBtn, b.pipBtn, b.shareBtn)",
    "press bounce",
)

kt = rep(
    kt,
    "        LiquidGlass.swell(b.nextPill, b.nextPill)\n",
    "        LiquidGlass.swell(b.nextPill, b.nextPill)\n        LiquidGlass.swell(b.infoChip, b.infoChip)\n",
    "info chip swell",
)

kt = rep(
    kt,
    "        super.onConfigurationChanged(newConfig)\n        grid.spanCount = computeSpan()",
    "        super.onConfigurationChanged(newConfig)\n        grid.spanCount = computeSpan()\n        updateChrome(newConfig)",
    "config change",
)

# ---------------- new resource files ----------------

files = {
    "app/src/main/res/values/player_dims.xml": '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <dimen name="vol_pill_width">132dp</dimen>
</resources>
''',
    "app/src/main/res/values-land/player_dims.xml": '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <dimen name="vol_pill_width">190dp</dimen>
</resources>
''',
    "app/src/main/res/drawable/ic_pip.xml": '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M19,11h-8v6h8v-6zM23,19V4.98C23,3.88 22.1,3 21,3H3c-1.1,0 -2,0.88 -2,1.98V19c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2zM21,19.02H3V4.97h18v14.05z" />
</vector>
''',
    "app/src/main/res/drawable/ic_share.xml": '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,2l-4,4 1.4,1.4L11,5.8V15h2V5.8l1.6,1.6L16,6z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M6,10h3v2H8v8h8v-8h-1v-2h3v12H6z" />
</vector>
''',
    "app/src/main/res/drawable/nav_glass_bg.xml": '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="?attr/colorSurfaceVariant" />
    <stroke
        android:width="1dp"
        android:color="#33FFFFFF" />
    <corners android:radius="30dp" />
</shape>
''',
}

# everything matched: write
MAIN.write_text(kt, encoding="utf-8")
LAYOUT.write_text(xml, encoding="utf-8")
for path, content in files.items():
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
print("patch_vlc_v5 applied")
