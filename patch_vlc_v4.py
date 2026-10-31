import re, os, sys

path = os.path.expanduser("~/PrismVLC/app/src/main/res/layout/activity_main.xml")
s = open(path, encoding="utf-8").read()

# 1) close (X) instead of the down chevron
assert s.count('android:src="@drawable/ic_chevron_down"') == 1, "chevron not found"
s = s.replace('android:src="@drawable/ic_chevron_down"', 'android:src="@drawable/ic_close"')

# 2) top-right: small lock circle + glass volume pill
new_right = '''            <ImageButton
                android:id="@+id/lockBtn"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:layout_marginEnd="8dp"
                android:background="@drawable/circle_dark"
                android:contentDescription="Lock controls"
                android:padding="11dp"
                android:scaleType="centerInside"
                android:src="@drawable/ic_lock_open"
                app:tint="@android:color/white" />

            <FrameLayout
                android:layout_width="156dp"
                android:layout_height="40dp"
                android:layout_marginEnd="8dp">

                <ImageView
                    android:id="@+id/blurLock"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:contentDescription="@null"
                    android:maxWidth="1dp"
                    android:maxHeight="1dp"
                    android:scaleType="matrix" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:paddingStart="8dp"
                    android:paddingEnd="14dp">

                    <com.example.myvlc.VolumeSlider
                        android:id="@+id/volumeSlider"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:progressBackgroundTint="#66FFFFFF"
                        android:progressTint="@android:color/white"
                        android:thumbTint="@android:color/white" />

                    <ImageView
                        android:layout_width="20dp"
                        android:layout_height="20dp"
                        android:layout_marginStart="6dp"
                        android:contentDescription="Volume"
                        android:src="@drawable/ic_volume" />
                </LinearLayout>
            </FrameLayout>
'''
pat = re.compile(
    r'            <FrameLayout\s+android:layout_width="46dp"\s+android:layout_height="46dp"\s+'
    r'android:layout_marginEnd="8dp">.*?</FrameLayout>\n', re.S)
m = pat.search(s)
assert m and 'blurLock' in m.group(0) and 'lockBtn' in m.group(0), "lock block not found"
s = s[:m.start()] + new_right + s[m.end():]

# 3) centre buttons a bit closer together, like the reference
assert s.count('android:layout_marginHorizontal="36dp"') == 1, "centre margin not found"
s = s.replace('android:layout_marginHorizontal="36dp"', 'android:layout_marginHorizontal="22dp"')

open(path, "w", encoding="utf-8").write(s)
print("patch_vlc_v4 applied")
