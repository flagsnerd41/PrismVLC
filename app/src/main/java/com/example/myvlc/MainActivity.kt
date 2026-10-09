package com.example.myvlc

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.graphics.RenderEffect
import android.graphics.Shader
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.myvlc.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private var pfd: ParcelFileDescriptor? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisible(false) }
    private var controlsVisible = true
    private var hasMedia = false
    private var dragging = false

    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    private var speedIndex = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { play(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        hideSystemBars()

        libVLC = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        player = MediaPlayer(libVLC)

        setupUi()
        setupPlayerEvents()
        intent?.data?.let { play(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { play(it) }
    }

    private fun setupUi() {
        // Blurred glow behind the play/pause button (real blur on Android 12+)
        if (Build.VERSION.SDK_INT >= 31) {
            b.playGlow.setRenderEffect(
                RenderEffect.createBlurEffect(45f, 45f, Shader.TileMode.CLAMP)
            )
        }

        val open = { picker.launch(arrayOf("video/*", "audio/*")) }
        b.emptyOpen.setOnClickListener { open() }
        b.openBtn.setOnClickListener { open() }

        b.playPause.setOnClickListener {
            b.playPause.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                b.playPause.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            if (player.isPlaying) player.pause() else player.play()
        }

        b.speedBtn.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            applySpeed()
        }

        b.seekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
                handler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(slider: Slider) {
                player.position = slider.value
                dragging = false
                scheduleHide()
            }
        })

        // Tap = show/hide controls, double tap left/right = seek -/+10s
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (hasMedia) setControlsVisible(!controlsVisible)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!hasMedia) return true
                val back = e.x < b.touchLayer.width / 2f
                val target = player.time + if (back) -10_000L else 10_000L
                player.time = target.coerceIn(0L, maxOf(player.length, 0L))
                return true
            }
        })
        b.touchLayer.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
    }

    private fun setupPlayerEvents() {
        player.setEventListener(MediaPlayer.EventListener { e ->
            runOnUiThread {
                when (e.type) {
                    MediaPlayer.Event.Playing -> { setPlayIcon(true); scheduleHide() }
                    MediaPlayer.Event.Paused -> { setPlayIcon(false); setControlsVisible(true) }
                    MediaPlayer.Event.Stopped, MediaPlayer.Event.EndReached -> {
                        setPlayIcon(false); setControlsVisible(true)
                    }
                    MediaPlayer.Event.LengthChanged -> b.durationText.text = fmt(e.lengthChanged)
                    MediaPlayer.Event.TimeChanged -> b.timeText.text = fmt(e.timeChanged)
                    MediaPlayer.Event.PositionChanged ->
                        if (!dragging) b.seekBar.value = e.positionChanged.coerceIn(0f, 1f)
                }
            }
        })
    }

    private fun play(uri: Uri) {
        try {
            pfd?.close()
            pfd = contentResolver.openFileDescriptor(uri, "r")
            val media = Media(libVLC, pfd!!.fileDescriptor)
            player.media = media
            media.release()
            player.play()
            applySpeed()
            hasMedia = true
            b.emptyOpen.visibility = View.GONE
            setControlsVisible(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySpeed() {
        val s = speeds[speedIndex]
        player.rate = s
        b.speedBtn.text = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "x"
    }

    private fun setPlayIcon(playing: Boolean) {
        b.playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setControlsVisible(show: Boolean) {
        controlsVisible = show
        handler.removeCallbacks(hideRunnable)
        if (show) {
            b.controls.visibility = View.VISIBLE
            b.controls.animate().alpha(1f).setDuration(200).start()
            scheduleHide()
        } else {
            b.controls.animate().alpha(0f).setDuration(250).withEndAction {
                if (!controlsVisible) b.controls.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (player.isPlaying && controlsVisible) handler.postDelayed(hideRunnable, 3000)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    override fun onStart() {
        super.onStart()
        player.attachViews(b.videoLayout, null, false, false)
    }

    override fun onStop() {
        super.onStop()
        if (player.isPlaying) player.pause()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.release()
        libVLC.release()
        pfd?.close()
    }
}
