package com.example.myvlc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the newest GitHub Release and tells the user when it is newer than the installed app. */
object Updater {
    const val REPO = "flagsnerd41/PrismVLC"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4201
    private const val PREFS = "prism_updater"

    data class Release(val versionCode: Long, val tag: String, val apkUrl: String, val notes: String)

    fun installedCode(ctx: Context): Long {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }

    /** Blocking network call - never run on the main thread. */
    fun fetchLatest(): Release? = try {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) null else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.value?.toLongOrNull()
            val assets = j.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            if (code == null || url == null) null else Release(code, tag, url, j.optString("body"))
        }
    } catch (e: Exception) { null }

    fun isNewer(ctx: Context, r: Release) = r.versionCode > installedCode(ctx)

    fun updateIntent(ctx: Context, r: Release) = Intent(ctx, UpdateActivity::class.java)
        .putExtra(UpdateActivity.EXTRA_URL, r.apkUrl)
        .putExtra(UpdateActivity.EXTRA_TAG, r.tag)
        .putExtra(UpdateActivity.EXTRA_NOTES, r.notes)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Check now; post a notification once per new release. Returns the new release if there is one. */
    fun checkAndNotify(ctx: Context): Release? {
        val r = fetchLatest() ?: return null
        if (!isNewer(ctx, r)) return null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("notified", 0L) >= r.versionCode) return r
        prefs.edit().putLong("notified", r.versionCode).apply()
        notify(ctx, r)
        return r
    }

    private fun notify(ctx: Context, r: Release) {
        val nm = NotificationManagerCompat.from(ctx)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, 0, updateIntent(ctx, r), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Prism update available")
            .setContentText("Tap to download and install the new version")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        nm.notify(NOTIF_ID, n)
    }
}
