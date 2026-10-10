package com.example.myvlc

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myvlc.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var adapter: VideoAdapter
    private var pfd: ParcelFileDescriptor? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisible(false) }
    private var controlsVisible = false
    private var textureView: TextureView? = null
    private lateinit var blurViews: List<ImageView>

    // Redraw the frosted glass every display frame (slower while paused)
    private val blurTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            updateBlur()
            if (controlsVisible && hasMedia) {
                if (player.isPlaying) Choreographer.getInstance().postFrameCallback(this)
                else handler.postDelayed(blurPost, 120)
            }
        }
    }
    private val blurPost = Runnable { Choreographer.getInstance().postFrameCallback(blurTick) }

    private fun startBlur() {
        stopBlur()
        Choreographer.getInstance().postFrameCallback(blurTick)
    }

    private fun stopBlur() {
        Choreographer.getInstance().removeFrameCallback(blurTick)
        handler.removeCallbacks(blurPost)
    }

    private var hasMedia = false
    private var dragging = false
    private var inLibrary = true
    private var needSettings = false

    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    private var speedIndex = 0

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else {
                needSettings = true
                showEmpty(true)
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showLibrary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        blurViews = listOf(b.blurBack, b.blurPlay, b.blurFwd, b.blurSpeed, b.blurLib)

        libVLC = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        player = MediaPlayer(libVLC)

        ViewCompat.setOnApplyWindowInsetsListener(b.libraryLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        adapter = VideoAdapter { play(it.uri) }
        b.videoList.layoutManager = LinearLayoutManager(this)
        b.videoList.adapter = adapter
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupUi()
        setupPlayerEvents()

        val uri = intent?.data
        if (uri != null) play(uri) else showLibrary()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { play(it) }
    }

    // ---------- Library ----------

    private fun videoPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, videoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun ensureLibrary() {
        if (hasPermission()) loadVideos()
        else if (!needSettings) permLauncher.launch(videoPermission())
        else showEmpty(true)
    }

    private fun showEmpty(noPermission: Boolean) {
        b.emptyText.visibility = View.VISIBLE
        b.emptyText.text = if (noPermission) "Allow access so Prism can find the videos on your device"
        else "No videos found on this device"
        b.grantBtn.visibility = if (noPermission) View.VISIBLE else View.GONE
    }

    private fun loadVideos() {
        b.emptyText.visibility = View.GONE
        b.grantBtn.visibility = View.GONE
        Thread {
            val list = mutableListOf<VideoItem>()
            try {
                val collection = if (Build.VERSION.SDK_INT >= 29)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION
                )
                contentResolver.query(
                    collection, proj, null, null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val name = (c.getString(nameCol) ?: "Video").substringBeforeLast('.')
                        list.add(
                            VideoItem(
                                ContentUris.withAppendedId(collection, id),
                                name,
                                c.getLong(durCol)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // ignore, shows empty state
            }
            runOnUiThread {
                adapter.submit(list)
                if (list.isEmpty()) showEmpty(false)
            }
        }.start()
    }

    private fun showLibrary() {
        inLibrary = true
        stopBlur()
        handler.removeCallbacksAndMessages(null)
        if (hasMedia) player.stop()
        hasMedia = false
        backCallback.isEnabled = false
        controlsVisible = false
        b.controls.visibility = View.GONE
        b.libraryLayout.visibility = View.VISIBLE

        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        c.isAppearanceLightStatusBars = !night
        c.isAppearanceLightNavigationBars = !night

        ensureLibrary()
    }

    private fun showPlayer() {
        inLibrary = false
        hasMedia = true
        backCallback.isEnabled = true
        b.libraryLayout.visibility = View.GONE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setControlsVisible(true)
    }

    // ---------- UI ----------

    private fun setupUi() {
        // Frosted glass behind every button: live blurred snapshot of the video
        for (v in blurViews) {
            v.clipToOutline = true
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0, 0, view.width, view.height,
                        minOf(view.width, view.height) / 2f
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                v.setRenderEffect(
                    RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP)
                )
            }
        }

        b.libBtn.setOnClickListener { showLibrary() }
        b.grantBtn.setOnClickListener {
            if (needSettings) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            } else {
                permLauncher.launch(videoPermission())
            }
        }

        b.playPause.setOnClickListener {
            b.playPause.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                b.playPause.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            if (player.isPlaying) player.pause() else player.play()
        }

        b.skipBack.setOnClickListener { seekBy(-10_000L); scheduleHide() }
        b.skipFwd.setOnClickListener { seekBy(10_000L); scheduleHide() }

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
                val back = e.x < b.touchLayer.width / 2f
                seekBy(if (back) -10_000L else 10_000L)
                return true
            }
        })
        b.touchLayer.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
    }

    private fun setupPlayerEvents() {
        player.setEventListener(MediaPlayer.EventListener { e ->
            runOnUiThread {
                if (inLibrary) return@runOnUiThread
                when (e.type) {
                    MediaPlayer.Event.Playing -> {
                        setPlayIcon(true); scheduleHide()
                        if (controlsVisible) startBlur()
                    }
                    MediaPlayer.Event.Paused -> { setPlayIcon(false); setControlsVisible(true) }
                    MediaPlayer.Event.Stopped, MediaPlayer.Event.EndReached -> {
                        setPlayIcon(false); setControlsVisible(true)
                    }
                    MediaPlayer.Event.LengthChanged -> b.durationText.text = formatTime(e.lengthChanged)
                    MediaPlayer.Event.TimeChanged -> b.timeText.text = formatTime(e.timeChanged)
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
            inLibrary = false
            player.play()
            applySpeed()
            showPlayer()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun seekBy(delta: Long) {
        if (!hasMedia) return
        player.time = (player.time + delta).coerceIn(0L, maxOf(player.length, 0L))
    }

    private fun applySpeed() {
        val s = speeds[speedIndex]
        player.rate = s
        b.speedBtn.text = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "x"
    }

    private fun setPlayIcon(playing: Boolean) {
        b.playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun updateBlur() {
        val tv = textureView ?: findTextureView(b.videoLayout)?.also { textureView = it } ?: return
        val scale = 12
        val bw = tv.width / scale
        val bh = tv.height / scale
        if (!tv.isAvailable || bw < 2 || bh < 2) return
        val bmp = tv.getBitmap(bw, bh) ?: return
        val sx = tv.width.toFloat() / bw
        val sy = tv.height.toFloat() / bh
        val tvLoc = IntArray(2)
        val loc = IntArray(2)
        tv.getLocationOnScreen(tvLoc)
        val m = Matrix()
        for (v in blurViews) {
            v.getLocationOnScreen(loc)
            m.setScale(sx, sy)
            m.postTranslate((tvLoc[0] - loc[0]).toFloat(), (tvLoc[1] - loc[1]).toFloat())
            v.setImageBitmap(bmp)
            v.imageMatrix = m
        }
    }

    private fun setControlsVisible(show: Boolean) {
        controlsVisible = show
        handler.removeCallbacks(hideRunnable)
        if (show) {
            b.controls.visibility = View.VISIBLE
            b.controls.animate().alpha(1f).setDuration(200).start()
            startBlur()
            scheduleHide()
        } else {
            stopBlur()
            b.controls.animate().alpha(0f).setDuration(250).withEndAction {
                if (!controlsVisible) b.controls.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (player.isPlaying && controlsVisible) handler.postDelayed(hideRunnable, 3000)
    }

    override fun onStart() {
        super.onStart()
        textureView = null
        player.attachViews(b.videoLayout, null, false, true)
        if (inLibrary && adapter.itemCount == 0 && hasPermission()) loadVideos()
    }

    override fun onStop() {
        super.onStop()
        stopBlur()
        if (player.isPlaying) player.pause()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBlur()
        handler.removeCallbacksAndMessages(null)
        player.release()
        libVLC.release()
        pfd?.close()
    }
}
