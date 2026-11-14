package com.example.myvlc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small transparent screen: asks, downloads the APK with a progress bar, then opens the installer. */
class UpdateActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TAG = "tag"
        const val EXTRA_NOTES = "notes"
    }

    private var dialog: AlertDialog? = null
    private var cancelled = false
    private var waitingForPermission = false
    private var downloading = false
    private lateinit var url: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val notes = intent.getStringExtra(EXTRA_NOTES).orEmpty().trim()
        dialog = AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(if (notes.isEmpty()) "A new version of Prism is ready." else notes.take(600))
            .setPositiveButton("Update") { _, _ -> start() }
            .setNegativeButton("Later") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPermission && packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            start()
        }
    }

    private fun start() {
        if (!packageManager.canRequestPackageInstalls()) {
            waitingForPermission = true
            Toast.makeText(this, "Allow Prism to install updates, then come back", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        if (downloading) return
        downloading = true
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(this).apply { text = "Downloading…"; setPadding(0, 0, 0, 24) }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 8)
            addView(label); addView(bar)
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle("Updating Prism").setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled = true; finish() }
            .show()
        Thread {
            val file = File(File(cacheDir, "updates").apply { mkdirs() }, "PrismVLC.apk")
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 20000
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            if (cancelled) return@Thread
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; runOnUiThread { bar.progress = p; label.text = "Downloading… $p%" } }
                            }
                        }
                    }
                }
                runOnUiThread { install(file) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }
}
