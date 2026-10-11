package com.example.myvlc

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Rational
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myvlc.databinding.ActivityMainBinding
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.RendererItem
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var adapter: LibraryAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var store: PlaylistStore
    private lateinit var progress: ProgressStore
    private lateinit var framePreview: FramePreview
    private lateinit var audio: AudioManager
    private lateinit var blurViews: List<ImageView>
    private lateinit var grid: GridLayoutManager
    private var pfd: ParcelFileDescriptor? = null
    private var density = 1f

    // themes
    private var themeIndex = 0
    private val themeNames = arrayOf(
        "Wallpaper colors (Material You)", "Ocean", "Sunset", "Forest", "Rose", "Violet", "AMOLED black"
    )
    private val themeStyles = intArrayOf(
        R.style.Theme_MyVLC, R.style.Theme_Prism_Ocean, R.style.Theme_Prism_Sunset,
        R.style.Theme_Prism_Forest, R.style.Theme_Prism_Rose, R.style.Theme_Prism_Violet,
        R.style.Theme_Prism_Amoled
    )

    // view settings
    private var listLayout = false
    private var cardSize = 1
    private var sortMode = 0
    private val sizeNames = arrayOf("Compact", "Normal", "Large")
    private val sortNames = arrayOf("Newest", "Name A-Z", "Longest", "Biggest", "Oldest")

    private val handler = Handler(Looper.getMainLooper())
    private val sleepHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisible(false) }
    private var controlsVisible = false
    private var textureView: TextureView? = null

    // Redraw the frosted glass every display frame (slower while paused)
    private val blurTick: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            updateBlur()
            if (controlsVisible && hasMedia) {
                if (player.isPlaying) Choreographer.getInstance().postFrameCallback(this)
                else handler.postDelayed(blurPost, 120)
            }
        }
    }
    private val blurPost: Runnable = Runnable { Choreographer.getInstance().postFrameCallback(blurTick) }

    private var hasMedia = false
    private var dragging = false
    private var inLibrary = true
    private var needSettings = false
    private var panelVisible = false
    private var locked = false
    private var lastPlaying = false
    private var lastSave = 0L

    // library data
    private var allVideos: List<VideoItem> = emptyList()
    private var byUri: Map<String, VideoItem> = emptyMap()
    private var allSeries: Map<String, List<VideoItem>> = emptyMap()
    private var folderMap: Map<String, List<VideoItem>> = emptyMap()
    private var currentSeries: String? = null
    private var currentPlaylist: String? = null
    private var currentFolder: String? = null
    private var tab = 0
    private var query = ""
    private var searchVisible = false

    // playback
    private var playlist: List<VideoItem> = emptyList()
    private var playIndex = -1
    private var currentUri: Uri? = null
    private var currentTitle = ""
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2
    private var abA = -1L
    private var abB = -1L
    private var aspectIndex = 0
    private var eqIndex = 0
    private var sleepIndex = 0
    private var pendingDelete: List<VideoItem> = emptyList()
    private val aspectNames = listOf("Best fit", "Fit screen", "Fill", "16:9", "4:3", "Original")
    private val sleepLabels = listOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes")
    private val sleepMinutes = intArrayOf(0, 15, 30, 45, 60)

    // gestures
    private var gMode = 0
    private var gDownX = 0f
    private var gDownY = 0f
    private var gStartBrightness = 0.5f
    private var gStartVolume = 0
    private var gStartTime = 0L
    private var gSeekTarget = -1L
    private var hintSeconds = 0
    private var hintLeftSide = false
    private val hideHintsRunnable = Runnable {
        for (v in listOf(b.hintLeft, b.hintRight)) {
            v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = View.GONE }.start()
        }
    }
    private val resetHintRunnable = Runnable { hintSeconds = 0 }
    private val hideHudRunnable = Runnable {
        b.hud.animate().alpha(0f).setDuration(200).withEndAction { b.hud.visibility = View.GONE }.start()
    }
    private val hideUnlockRunnable = Runnable {
        b.unlockBtn.animate().alpha(0f).setDuration(200).withEndAction { b.unlockBtn.visibility = View.GONE }.start()
    }

    // cast
    private var discoverer: RendererDiscoverer? = null
    private val renderers = mutableListOf<RendererItem>()
    private var castIndex = 0

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else {
                needSettings = true
                showEmpty(true)
            }
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                for (item in pendingDelete) {
                    progress.remove(item.uri.toString())
                    for (name in store.names()) store.remove(name, item.uri.toString())
                }
                pendingDelete = emptyList()
                toast("Deleted")
                loadVideos()
            }
        }

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { addSubtitle(it) }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (locked) showUnlockTemporarily()
            else if (panelVisible) hidePanel()
            else if (!inLibrary) showLibrary()
            else if (searchVisible) closeSearch()
            else if (currentSeries != null || currentPlaylist != null || currentFolder != null) libraryUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        prefs = getSharedPreferences("prism", MODE_PRIVATE)
        store = PlaylistStore(prefs)
        progress = ProgressStore(getSharedPreferences("prism_progress", MODE_PRIVATE))
        themeIndex = prefs.getInt("theme", 0).coerceIn(0, themeStyles.size - 1)
        listLayout = prefs.getBoolean("list", false)
        cardSize = prefs.getInt("size", 1).coerceIn(0, 2)
        sortMode = prefs.getInt("sort", 0).coerceIn(0, 4)
        setTheme(themeStyles[themeIndex])
        if (themeIndex == 0) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val wlp = window.attributes
        wlp.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE
        window.attributes = wlp
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        blurViews = listOf(
            b.blurPrev, b.blurPlay, b.blurNext, b.blurPill, b.blurNextPill, b.blurPanel,
            b.blurBottom
        )
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        framePreview = FramePreview(this)

        libVLC = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        player = MediaPlayer(libVLC)

        // Keep the title clear of the selfie camera / notch (and side cutouts in landscape)
        ViewCompat.setOnApplyWindowInsetsListener(b.controls) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val top = maxOf(cutout.top, bars.top)
            val left = maxOf(cutout.left, bars.left)
            val right = maxOf(cutout.right, bars.right)
            b.topBar.setPadding(
                maxOf((8 * density).toInt(), left),
                top + (16 * density).toInt(),
                maxOf((8 * density).toInt(), right),
                0
            )
            b.bottomBar.setPadding(
                maxOf((16 * density).toInt(), left),
                0,
                maxOf((16 * density).toInt(), right),
                (12 * density).toInt()
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.libraryLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        adapter = LibraryAdapter(
            onSeries = { name -> openSeries(name) },
            onFolder = { name -> openFolder(name) },
            onVideo = { item -> onVideoClicked(item) },
            onVideoLong = { item -> showVideoMenu(item) },
            onPlaylist = { name -> openPlaylist(name) },
            onPlaylistLong = { name -> showPlaylistMenu(name) },
            onNewPlaylist = {
                createPlaylistFlow { name ->
                    openPlaylist(name)
                    showAddVideosDialog(name)
                }
            },
            onAddVideos = { currentPlaylist?.let { showAddVideosDialog(it) } }
        )
        grid = GridLayoutManager(this, computeSpan())
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isFullSpan(position)) grid.spanCount else 1
        }
        b.videoList.layoutManager = grid
        b.videoList.adapter = adapter
        b.videoList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val off = recyclerView.computeVerticalScrollOffset()
                val f = (off / (90f * density)).coerceIn(0f, 1f)
                b.libTitle.textSize = 30f - 8f * f
            }
        })
        b.refresh.setColorSchemeColors(themeColor(androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
        b.refresh.setProgressBackgroundColorSchemeColor(
            themeColor(com.google.android.material.R.attr.colorSurfaceVariant, Color.DKGRAY)
        )
        b.refresh.setOnRefreshListener {
            if (hasPermission()) loadVideos() else {
                b.refresh.isRefreshing = false
                ensureLibrary()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupUi()
        setupPlayerEvents()

        val uri = intent?.data
        if (uri != null) playExternal(uri) else showLibrary()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { playExternal(it) }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
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
        b.refresh.isRefreshing = false
        b.emptyLogo.visibility = View.VISIBLE
        b.emptyText.visibility = View.VISIBLE
        b.emptyText.text = if (noPermission) "Allow access so Prism can find the videos on your device"
        else "No videos found on this device"
        b.grantBtn.visibility = if (noPermission) View.VISIBLE else View.GONE
    }

    private fun loadVideos() {
        b.emptyLogo.visibility = View.GONE
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
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED
                )
                contentResolver.query(
                    collection, proj, null, null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val name = (c.getString(nameCol) ?: "Video").substringBeforeLast('.')
                        list.add(
                            VideoItem(
                                uri = ContentUris.withAppendedId(collection, id),
                                title = name,
                                durationMs = c.getLong(durCol),
                                size = c.getLong(sizeCol),
                                width = c.getInt(wCol),
                                height = c.getInt(hCol),
                                folder = c.getString(folderCol) ?: "",
                                added = c.getLong(addedCol)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // ignore, shows empty state
            }

            // Detect series: same show name with episode numbers (2+ files)
            val parsed = list.map { item -> Pair(item, parseEpisode(item.title)) }
            val groups = parsed.filter { it.second != null }.groupBy { it.second!!.name.lowercase() }
            val seriesMap = LinkedHashMap<String, List<VideoItem>>()
            val enriched = HashMap<String, VideoItem>()
            for (g in groups.values) {
                if (g.size >= 2) {
                    val display = g.first().second!!.name
                    val episodes = g.map { pair ->
                        pair.first.copy(
                            series = display,
                            season = pair.second!!.season,
                            episode = pair.second!!.episode
                        )
                    }.sortedWith(compareBy({ it.season }, { it.episode }, { it.title }))
                    seriesMap[display] = episodes
                    for (ep in episodes) enriched[ep.uri.toString()] = ep
                }
            }
            val all = list.map { enriched[it.uri.toString()] ?: it }
            val sortedSeries = seriesMap.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            val folders = all.groupBy { if (it.folder.isEmpty()) "Other" else it.folder }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)

            runOnUiThread {
                allVideos = all
                byUri = all.associateBy { it.uri.toString() }
                allSeries = sortedSeries
                folderMap = folders
                if (currentSeries != null && !sortedSeries.containsKey(currentSeries)) currentSeries = null
                if (currentFolder != null && !folders.containsKey(currentFolder)) currentFolder = null
                if (currentPlaylist != null && !store.names().contains(currentPlaylist)) currentPlaylist = null
                b.refresh.isRefreshing = false
                applyLibraryHeader()
                rebuildRows()
                b.videoList.scheduleLayoutAnimation()
                if (list.isEmpty()) showEmpty(false)
            }
        }.start()
    }

    private fun playlistVideos(name: String): List<VideoItem> =
        store.uris(name).mapNotNull { byUri[it] }

    private fun sortVideos(list: List<VideoItem>): List<VideoItem> = when (sortMode) {
        1 -> list.sortedBy { it.title.lowercase() }
        2 -> list.sortedByDescending { it.durationMs }
        3 -> list.sortedByDescending { it.size }
        4 -> list.sortedBy { it.added }
        else -> list.sortedByDescending { it.added }
    }

    private fun continueWatching(): List<Pair<VideoItem, Float>> {
        val found = ArrayList<Triple<VideoItem, Float, Long>>()
        for ((uri, e) in progress.all()) {
            val item = byUri[uri] ?: continue
            if (e.dur <= 0L) continue
            val f = e.pos.toFloat() / e.dur
            if (e.pos > 5000L && f < 0.95f) found.add(Triple(item, f, e.time))
        }
        return found.sortedByDescending { it.third }.take(6).map { Pair(it.first, it.second) }
    }

    private fun rebuildRows() {
        val rows = ArrayList<Row>()
        val cs = currentSeries
        val cp = currentPlaylist
        val cf = currentFolder
        var listMode = listLayout
        if (cp != null) {
            listMode = true
            rows.add(Row.AddVideos)
            playlistVideos(cp).forEach { rows.add(Row.VideoRow(it)) }
        } else if (cs != null) {
            listMode = true
            allSeries[cs]?.forEach { rows.add(Row.VideoRow(it)) }
        } else if (cf != null) {
            sortVideos(folderMap[cf] ?: emptyList()).forEach { rows.add(Row.VideoRow(it)) }
        } else if (tab == 1) {
            if (allSeries.isEmpty()) {
                rows.add(Row.Note("No series found yet.\nName files like  Show S01E01  and they will appear here."))
            } else {
                for ((name, eps) in allSeries) rows.add(Row.SeriesRow(name, eps.size, eps.first().uri))
            }
        } else if (tab == 2) {
            rows.add(Row.NewPlaylist)
            for (name in store.names()) {
                val vids = playlistVideos(name)
                rows.add(Row.PlaylistRow(name, vids.size, vids.firstOrNull()?.uri))
            }
        } else if (tab == 3) {
            if (folderMap.isEmpty()) {
                rows.add(Row.Note("No folders yet"))
            } else {
                for ((name, vids) in folderMap) rows.add(Row.FolderRow(name, vids.size, vids.first().uri))
            }
        } else {
            val q = query.lowercase()
            if (q.isNotEmpty()) {
                val found = sortVideos(allVideos.filter { it.title.lowercase().contains(q) })
                if (found.isEmpty()) rows.add(Row.Note("No videos match your search"))
                else found.forEach { rows.add(Row.VideoRow(it)) }
            } else {
                val cont = continueWatching()
                if (cont.isNotEmpty()) {
                    rows.add(Row.Header("Continue watching"))
                    cont.forEach { rows.add(Row.VideoRow(it.first, it.second)) }
                }
                if (allVideos.size > 8) {
                    rows.add(Row.Header("Recently added"))
                    allVideos.take(6).forEach { rows.add(Row.VideoRow(it)) }
                }
                if (allVideos.isNotEmpty()) {
                    rows.add(Row.Header("All videos"))
                    sortVideos(allVideos).forEach { rows.add(Row.VideoRow(it)) }
                }
            }
        }
        adapter.submit(rows, listMode)
    }

    private fun applyLibraryHeader() {
        val title = currentSeries ?: currentPlaylist ?: currentFolder
        val detail = title != null
        if (detail) closeSearch()
        b.libTitle.text = title ?: "Prism"
        b.libBack.visibility = if (detail) View.VISIBLE else View.GONE
        b.libLogo.visibility = if (detail) View.GONE else View.VISIBLE
        b.searchBtn.visibility = if (detail) View.GONE else View.VISIBLE
        b.searchBox.visibility = if (!detail && searchVisible) View.VISIBLE else View.GONE
        if (inLibrary) backCallback.isEnabled = detail || searchVisible
    }

    private fun toggleSearch() {
        searchVisible = !searchVisible
        b.searchBox.visibility = if (searchVisible) View.VISIBLE else View.GONE
        if (searchVisible) {
            b.searchBox.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(b.searchBox, 0)
        } else {
            b.searchBox.setText("")
            hideKeyboard()
        }
        if (inLibrary) backCallback.isEnabled = searchVisible || currentSeries != null ||
            currentPlaylist != null || currentFolder != null
    }

    private fun closeSearch() {
        if (!searchVisible) return
        searchVisible = false
        b.searchBox.visibility = View.GONE
        b.searchBox.setText("")
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.searchBox.windowToken, 0)
    }

    private fun computeSpan(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val factor = when (cardSize) {
            0 -> 0.75f
            2 -> 1.4f
            else -> 1f
        }
        return (widthDp / (170f * factor)).toInt().coerceIn(2, 6)
    }

    /** direction: 1 = slide in from the right (open), -1 = from the left (back), 0 = just pop in. */
    private fun refreshLibrary(direction: Int = 0) {
        applyLibraryHeader()
        rebuildRows()
        b.videoList.scrollToPosition(0)
        b.libTitle.textSize = 30f
        b.videoList.scheduleLayoutAnimation()
        if (direction != 0) {
            b.videoList.animate().cancel()
            b.videoList.translationX = direction * 48f * density
            b.videoList.alpha = 0f
            b.videoList.animate().translationX(0f).alpha(1f).setDuration(260).start()
            b.libTitle.alpha = 0f
            b.libTitle.animate().alpha(1f).setDuration(260).start()
        }
    }

    private fun refreshKeepScroll() {
        applyLibraryHeader()
        rebuildRows()
    }

    private fun openSeries(name: String) {
        currentSeries = name
        currentPlaylist = null
        currentFolder = null
        refreshLibrary(1)
    }

    private fun openPlaylist(name: String) {
        currentPlaylist = name
        currentSeries = null
        currentFolder = null
        refreshLibrary(1)
    }

    private fun openFolder(name: String) {
        currentFolder = name
        currentSeries = null
        currentPlaylist = null
        refreshLibrary(1)
    }

    private fun libraryUp() {
        currentSeries = null
        currentPlaylist = null
        currentFolder = null
        refreshLibrary(-1)
    }

    private fun onVideoClicked(item: VideoItem) {
        val cp = currentPlaylist
        val cs = currentSeries
        val cf = currentFolder
        val itemSeries = item.series
        if (cp != null) {
            val vids = playlistVideos(cp)
            startPlaylist(vids, vids.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0))
        } else if (cs != null) {
            val eps = allSeries[cs] ?: listOf(item)
            startPlaylist(eps, eps.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0))
        } else if (cf != null) {
            val vids = sortVideos(folderMap[cf] ?: listOf(item))
            startPlaylist(vids, vids.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0))
        } else if (itemSeries != null) {
            val eps = allSeries[itemSeries] ?: listOf(item)
            startPlaylist(eps, eps.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0))
        } else {
            startPlaylist(listOf(item), 0)
        }
    }

    // ---------- Dialogs: playlists, delete, info, share ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun askName(title: String, initial: String, positive: String, onDone: (String) -> Unit) {
        val input = EditText(this)
        input.hint = "Playlist name"
        input.setSingleLine()
        input.setText(initial)
        input.setSelection(input.text.length)
        val box = FrameLayout(this)
        val pad = (20 * density).toInt()
        box.setPadding(pad, (8 * density).toInt(), pad, 0)
        box.addView(
            input,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(positive) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createPlaylistFlow(then: (String) -> Unit) {
        askName("New playlist", "", "Create") { typed ->
            then(store.create(typed))
        }
    }

    private fun showAddVideosDialog(name: String) {
        val vids = allVideos
        if (vids.isEmpty()) {
            toast("No videos found yet")
            return
        }
        val current = store.uris(name).toSet()
        val titles = vids.map { it.title }.toTypedArray()
        val checked = BooleanArray(vids.size) { vids[it].uri.toString() in current }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add videos to " + name)
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Done") { _, _ ->
                val existing = store.uris(name)
                val selected = vids.indices.filter { checked[it] }.map { vids[it].uri.toString() }
                val kept = existing.filter { it in selected }
                val added = selected.filter { it !in existing }
                store.setAll(name, kept + added)
                refreshKeepScroll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddToPlaylist(item: VideoItem) {
        val uri = item.uri.toString()
        val names = store.names()
        val labels = ArrayList<String>(names)
        labels.add("New playlist…")
        MaterialAlertDialogBuilder(this)
            .setTitle("Add to playlist")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < names.size) {
                    store.add(names[which], uri)
                    toast("Added to " + names[which])
                    refreshKeepScroll()
                } else {
                    createPlaylistFlow { created ->
                        store.add(created, uri)
                        toast("Added to " + created)
                        refreshKeepScroll()
                    }
                }
            }
            .show()
    }

    private fun showVideoMenu(item: VideoItem) {
        val cp = currentPlaylist
        val options = ArrayList<String>()
        if (cp != null) options.add("Remove from this playlist")
        options.add("Add to playlist")
        options.add("Video info")
        options.add("Share")
        options.add("Delete video")
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Remove from this playlist" -> {
                        if (cp != null) store.remove(cp, item.uri.toString())
                        refreshKeepScroll()
                    }
                    "Video info" -> showInfo(item)
                    "Share" -> shareVideo(item)
                    "Delete video" -> confirmDelete(item)
                    else -> showAddToPlaylist(item)
                }
            }
            .show()
    }

    private fun showInfo(item: VideoItem) {
        val sb = StringBuilder()
        sb.append("Name: ").append(item.title).append("\n")
        sb.append("Duration: ").append(formatTime(item.durationMs)).append("\n")
        if (item.width > 0 && item.height > 0) {
            sb.append("Resolution: ").append(item.width).append(" x ").append(item.height).append("\n")
        }
        sb.append("Size: ").append(formatSize(item.size)).append("\n")
        if (item.folder.isNotEmpty()) sb.append("Folder: ").append(item.folder)
        MaterialAlertDialogBuilder(this)
            .setTitle("Video info")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun shareVideo(item: VideoItem) {
        try {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "video/*"
            send.putExtra(Intent.EXTRA_STREAM, item.uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share video"))
        } catch (e: Exception) {
            toast("Can't share this video")
        }
    }

    private fun confirmDelete(item: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this video?")
            .setMessage("\"" + item.title + "\" will be permanently removed from your phone.")
            .setPositiveButton("Delete") { _, _ -> requestDelete(listOf(item)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestDelete(items: List<VideoItem>) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                pendingDelete = items
                val pi = MediaStore.createDeleteRequest(contentResolver, items.map { it.uri })
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                toast("Can't delete this video")
            }
        } else {
            toast("Deleting videos needs Android 11 or newer")
        }
    }

    private fun showPlaylistMenu(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) {
                    askName("Rename playlist", name, "Save") { typed ->
                        store.rename(name, typed)
                        refreshKeepScroll()
                    }
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Delete this playlist?")
                        .setMessage("Your videos are not deleted, only the playlist.")
                        .setPositiveButton("Delete") { _, _ ->
                            store.delete(name)
                            refreshKeepScroll()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun showThemeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(themeNames, themeIndex) { dialog, which ->
                dialog.dismiss()
                if (which != themeIndex) {
                    prefs.edit().putInt("theme", which).apply()
                    recreate()
                }
            }
            .show()
    }

    private fun showMenuDialog() {
        val items = arrayOf(
            "Theme",
            if (listLayout) "Switch to grid view" else "Switch to list view",
            "Card size: " + sizeNames[cardSize],
            "Sort by: " + sortNames[sortMode],
            "About Prism"
        )
        MaterialAlertDialogBuilder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemeDialog()
                    1 -> {
                        listLayout = !listLayout
                        prefs.edit().putBoolean("list", listLayout).apply()
                        refreshLibrary()
                    }
                    2 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Card size")
                        .setSingleChoiceItems(sizeNames, cardSize) { d, w ->
                            d.dismiss()
                            cardSize = w
                            prefs.edit().putInt("size", w).apply()
                            grid.spanCount = computeSpan()
                            refreshKeepScroll()
                        }
                        .show()
                    3 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Sort by")
                        .setSingleChoiceItems(sortNames, sortMode) { d, w ->
                            d.dismiss()
                            sortMode = w
                            prefs.edit().putInt("sort", w).apply()
                            refreshKeepScroll()
                        }
                        .show()
                    else -> MaterialAlertDialogBuilder(this)
                        .setTitle("Prism")
                        .setMessage(
                            "A frosted-glass video player built on libVLC.\n\n" +
                                "Unofficial, not affiliated with VideoLAN."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    // ---------- Pages: library <-> player (+ mini player) ----------

    private fun isDarkUi(): Boolean {
        if (themeIndex != 0) return true
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun showLibrary() {
        inLibrary = true
        hidePanel()
        stopBlur()
        handler.removeCallbacksAndMessages(null)
        locked = false
        b.unlockBtn.visibility = View.GONE
        saveProgress()
        setBrightness(-1f)
        controlsVisible = false
        b.controls.visibility = View.GONE
        showLibraryAnimated()
        lastPlaying = player.isPlaying
        setPlayIconStatic(lastPlaying)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyLibraryHeader()
        updateMini()

        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
        val dark = isDarkUi()
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark

        ensureLibrary()
    }

    private fun showPlayer() {
        inLibrary = false
        hasMedia = true
        backCallback.isEnabled = true
        hideLibraryAnimated()
        b.miniPlayer.visibility = View.GONE
        lastPlaying = player.isPlaying
        setPlayIconStatic(lastPlaying)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setControlsVisible(true)
    }

    private fun showLibraryAnimated() {
        b.libraryLayout.animate().cancel()
        b.libraryLayout.alpha = 0f
        b.libraryLayout.scaleX = 0.96f
        b.libraryLayout.scaleY = 0.96f
        b.libraryLayout.visibility = View.VISIBLE
        b.libraryLayout.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
    }

    private fun hideLibraryAnimated() {
        b.libraryLayout.animate().cancel()
        b.libraryLayout.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(240)
            .withEndAction {
                if (!inLibrary) b.libraryLayout.visibility = View.GONE
                b.libraryLayout.alpha = 1f
                b.libraryLayout.scaleX = 1f
                b.libraryLayout.scaleY = 1f
            }.start()
    }

    private fun updateMini() {
        val show = hasMedia && inLibrary
        if (!show) {
            b.miniPlayer.visibility = View.GONE
            return
        }
        if (b.miniPlayer.visibility != View.VISIBLE) {
            b.miniPlayer.alpha = 0f
            b.miniPlayer.visibility = View.VISIBLE
            b.miniPlayer.animate().alpha(1f).setDuration(220).start()
        }
        b.miniTitle.text = currentTitle
        val u = currentUri
        if (u != null) loadCover(b.miniThumb, u)
        updateMiniIcon()
    }

    private fun updateMiniIcon() {
        b.miniPlay.setImageResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun closeMini() {
        saveProgress()
        player.stop()
        hasMedia = false
        currentUri = null
        playlist = emptyList()
        playIndex = -1
        framePreview.setSource(null)
        updateMini()
    }

    private fun returnToPlayer() {
        if (hasMedia) showPlayer()
    }

    // ---------- Playlist / playback ----------

    private fun startPlaylist(list: List<VideoItem>, index: Int) {
        playlist = list
        playIndex = index
        playCurrent(true)
    }

    private fun currentItem(): VideoItem? = playlist.getOrNull(playIndex)

    private fun playCurrent(showUi: Boolean) {
        val item = playlist.getOrNull(playIndex) ?: return
        currentTitle = item.title
        b.titleText.text = item.title
        if (item.series != null) {
            b.subtitleText.text = String.format("%s · S%02dE%02d", item.series, item.season, item.episode)
            b.subtitleText.visibility = View.VISIBLE
        } else {
            b.subtitleText.visibility = View.GONE
        }
        updateNavButtons()
        startMedia(item.uri, showUi)
    }

    private fun playExternal(uri: Uri) {
        playlist = emptyList()
        playIndex = -1
        currentTitle = uri.lastPathSegment ?: "Video"
        b.titleText.text = currentTitle
        b.subtitleText.visibility = View.GONE
        updateNavButtons()
        startMedia(uri, true)
    }

    private fun hasNext() = playIndex in 0 until playlist.size - 1

    private fun playNext() {
        if (hasNext()) {
            playIndex++
            playCurrent(!inLibrary)
        }
    }

    private fun playPrev() {
        if (playIndex > 0) {
            playIndex--
            playCurrent(!inLibrary)
        } else if (hasMedia) {
            player.time = 0L
        }
    }

    private fun updateNavButtons() {
        b.prevBtn.alpha = if (playIndex > 0) 1f else 0.45f
        b.nextBtn.alpha = if (hasNext()) 1f else 0.45f
        if (hasNext()) {
            b.nextPill.visibility = View.VISIBLE
            Glide.with(b.nextThumb).load(playlist[playIndex + 1].uri)
                .placeholder(R.drawable.cover_placeholder)
                .error(R.drawable.cover_placeholder)
                .fallback(R.drawable.cover_placeholder)
                .centerCrop().into(b.nextThumb)
        } else {
            b.nextPill.visibility = View.GONE
        }
    }

    private fun startMedia(uri: Uri, showUi: Boolean) {
        try {
            saveProgress()
            val old = pfd
            val fd = contentResolver.openFileDescriptor(uri, "r")
            pfd = fd
            val media = Media(libVLC, fd!!.fileDescriptor)
            player.media = media
            media.release()
            old?.close()
            currentUri = uri
            abA = -1L
            abB = -1L
            framePreview.setSource(uri)
            hasMedia = true
            if (showUi) inLibrary = false
            player.play()
            applySpeed()
            resumeIfSaved(uri)
            if (showUi) showPlayer() else updateMini()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resumeIfSaved(uri: Uri) {
        val e = progress.get(uri.toString()) ?: return
        val frac = if (e.dur > 0L) e.pos.toFloat() / e.dur else 0f
        if (e.pos > 5000L && frac < 0.95f) {
            handler.postDelayed({
                player.time = e.pos
                toast("Resumed at " + formatTime(e.pos))
            }, 700)
        }
    }

    private fun saveProgress() {
        val u = currentUri ?: return
        if (!hasMedia) return
        val len = player.length
        val pos = player.time
        if (len <= 0L || pos <= 0L) return
        if (pos > len * 0.95) progress.remove(u.toString()) else progress.save(u.toString(), pos, len)
    }

    private fun onEndReached() {
        currentUri?.let { progress.remove(it.toString()) }
        if (hasNext()) {
            playIndex++
            playCurrent(!inLibrary)
        } else if (inLibrary) {
            closeMini()
        } else {
            setPlayIcon(false)
            setControlsVisible(true)
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

    private fun setPlayIconStatic(playing: Boolean) {
        b.playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setPlayIcon(playing: Boolean) {
        if (lastPlaying == playing) return
        lastPlaying = playing
        // the play and pause shapes morph into each other
        b.playPause.setImageResource(
            if (playing) R.drawable.avd_play_to_pause else R.drawable.avd_pause_to_play
        )
        (b.playPause.drawable as? Animatable)?.start()
    }

    private fun nudge(v: View, direction: Int) {
        val dist = 8f * density * direction
        v.animate().translationX(dist).setDuration(90).withEndAction {
            v.animate().translationX(0f).setDuration(220)
                .setInterpolator(OvershootInterpolator()).start()
        }.start()
    }

    private fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
        window.attributes = lp
    }

    // ---------- UI wiring ----------

    private fun setupUi() {
        // Heavy frosted glass behind every button: live blurred snapshot of the video
        for (v in blurViews) {
            val isBand = v === b.blurBottom
            if (!isBand) {
                v.clipToOutline = true
                v.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val r = if (view === b.blurPanel) 24f * view.resources.displayMetrics.density
                        else minOf(view.width, view.height) / 2f
                        outline.setRoundRect(0, 0, view.width, view.height, r)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val radius = if (isBand) 40f else 32f
                v.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            }
        }

        // library
        b.menuBtn.setOnClickListener { showMenuDialog() }
        b.searchBtn.setOnClickListener { toggleSearch() }
        b.searchBox.doAfterTextChanged { text ->
            query = (text?.toString() ?: "").trim()
            if (query.isNotEmpty() && tab != 0) b.bottomNav.selectedItemId = R.id.nav_videos
            else refreshKeepScroll()
        }
        b.searchBox.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        b.bottomNav.setOnItemSelectedListener { item ->
            tab = when (item.itemId) {
                R.id.nav_series -> 1
                R.id.nav_playlists -> 2
                R.id.nav_folders -> 3
                else -> 0
            }
            currentSeries = null
            currentPlaylist = null
            currentFolder = null
            refreshLibrary()
            true
        }
        b.libBack.setOnClickListener { libraryUp() }
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

        // mini player
        b.miniPlayer.setOnClickListener { returnToPlayer() }
        b.miniPlay.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
        }
        b.miniClose.setOnClickListener { closeMini() }

        // player buttons
        b.backBtn.setOnClickListener { showLibrary() }
        b.lockBtn.setOnClickListener { setLocked(true) }
        b.unlockBtn.setOnClickListener { setLocked(false) }
        b.prevBtn.setOnClickListener { tick(it); nudge(b.prevBtn, -1); playPrev() }
        b.nextBtn.setOnClickListener { tick(it); nudge(b.nextBtn, 1); playNext() }
        b.nextPill.setOnClickListener { tick(it); playNext() }
        b.ccBtn.setOnClickListener { tick(it); showSubtitlePanel() }
        b.audioBtn.setOnClickListener { tick(it); showAudioPanel() }
        b.castBtn.setOnClickListener { tick(it); showCastPanel() }
        b.speedBtn.setOnClickListener { tick(it); showSpeedPanel() }
        b.moreBtn.setOnClickListener { tick(it); showMorePanel() }
        b.rotateBtn.setOnClickListener { tick(it); toggleOrientation() }

        b.playPause.setOnClickListener {
            tick(it)
            b.playPause.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                b.playPause.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            if (player.isPlaying) player.pause() else player.play()
        }

        // progress bar + frame preview
        b.seekBar.onSeekStart = {
            dragging = true
            handler.removeCallbacks(hideRunnable)
        }
        b.seekBar.onSeekPreview = { p ->
            val t = (p * maxOf(player.length, 0L)).toLong()
            b.timeText.text = formatTime(t)
            framePreview.request(t) { bmp ->
                if (dragging) {
                    b.previewImage.setImageBitmap(bmp)
                    b.previewCard.visibility = View.VISIBLE
                    positionPreview()
                }
            }
            positionPreview()
        }
        b.seekBar.onSeekEnd = { p ->
            player.position = p
            dragging = false
            b.previewCard.visibility = View.GONE
            scheduleHide()
        }

        // gestures: tap, double-tap, swipe (brightness / volume / seek)
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) showUnlockTemporarily()
                else if (panelVisible) hidePanel()
                else if (hasMedia) setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked || !hasMedia) return true
                val back = e.x < b.touchLayer.width / 2f
                seekBy(if (back) -10_000L else 10_000L)
                showSeekHint(back)
                return true
            }
        })
        b.touchLayer.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            handleGesture(ev)
            true
        }
    }

    private fun handleGesture(ev: MotionEvent) {
        if (locked || !hasMedia || inLibrary) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gMode = 0
                gSeekTarget = -1L
                gDownX = ev.x
                gDownY = ev.y
                val cur = window.attributes.screenBrightness
                gStartBrightness = if (cur < 0f) 0.5f else cur
                gStartVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                gStartTime = player.time
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - gDownX
                val dy = ev.y - gDownY
                val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
                if (gMode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    gMode = if (abs(dy) > abs(dx)) {
                        if (gDownX < b.touchLayer.width / 2f) 1 else 2
                    } else 3
                }
                val h = maxOf(b.touchLayer.height, 1) * 0.8f
                if (gMode == 1) {
                    val v = (gStartBrightness - dy / h).coerceIn(0.02f, 1f)
                    setBrightness(v)
                    showHud("Brightness  " + (v * 100).roundToInt() + "%", v)
                } else if (gMode == 2) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (gStartVolume + (-dy / h) * max).roundToInt().coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    showHud("Volume  " + (v * 100 / maxOf(max, 1)) + "%", v.toFloat() / maxOf(max, 1))
                } else if (gMode == 3) {
                    val delta = (dx / maxOf(b.touchLayer.width, 1) * 90_000f).toLong()
                    gSeekTarget = (gStartTime + delta).coerceIn(0L, maxOf(player.length, 0L))
                    val secs = delta / 1000
                    showHud(
                        formatTime(gSeekTarget) + "   (" + (if (secs >= 0) "+" else "") + secs + "s)",
                        null
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gMode == 3 && gSeekTarget >= 0L && ev.actionMasked == MotionEvent.ACTION_UP) {
                    player.time = gSeekTarget
                }
                gMode = 0
                gSeekTarget = -1L
            }
        }
    }

    private fun showHud(text: String, fraction: Float?) {
        b.hudText.text = text
        if (fraction != null) {
            b.hudBar.visibility = View.VISIBLE
            b.hudBar.setProgressCompat((fraction * 100).toInt(), false)
        } else {
            b.hudBar.visibility = View.GONE
        }
        b.hud.animate().cancel()
        b.hud.alpha = 1f
        b.hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, 800)
    }

    private fun showSeekHint(left: Boolean) {
        if (left != hintLeftSide) hintSeconds = 0
        hintLeftSide = left
        hintSeconds += 10
        val v = if (left) b.hintLeft else b.hintRight
        val other = if (left) b.hintRight else b.hintLeft
        other.visibility = View.GONE
        v.text = if (left) "◀◀   " + hintSeconds + "s" else "" + hintSeconds + "s   ▶▶"
        v.animate().cancel()
        v.alpha = 1f
        v.scaleX = 0.9f
        v.scaleY = 0.9f
        v.visibility = View.VISIBLE
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        handler.removeCallbacks(hideHintsRunnable)
        handler.removeCallbacks(resetHintRunnable)
        handler.postDelayed(hideHintsRunnable, 600)
        handler.postDelayed(resetHintRunnable, 900)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        if (value) {
            hidePanel()
            setControlsVisible(false)
            toast("Controls locked")
            showUnlockTemporarily()
        } else {
            b.unlockBtn.visibility = View.GONE
            setControlsVisible(true)
        }
    }

    private fun showUnlockTemporarily() {
        b.unlockBtn.animate().cancel()
        b.unlockBtn.alpha = 1f
        b.unlockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlockRunnable)
        handler.postDelayed(hideUnlockRunnable, 2500)
    }

    private fun setupPlayerEvents() {
        player.setEventListener(MediaPlayer.EventListener { e ->
            runOnUiThread {
                if (inLibrary) {
                    // mini player: keep its icon and progress up to date
                    when (e.type) {
                        MediaPlayer.Event.Playing, MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped ->
                            updateMiniIcon()
                        MediaPlayer.Event.PositionChanged ->
                            b.miniProgress.progress = (e.positionChanged * 1000).toInt()
                        MediaPlayer.Event.EndReached -> onEndReached()
                        else -> {}
                    }
                    return@runOnUiThread
                }
                when (e.type) {
                    MediaPlayer.Event.Playing -> {
                        setPlayIcon(true)
                        scheduleHide()
                        if (controlsVisible) startBlur()
                    }
                    MediaPlayer.Event.Paused -> { setPlayIcon(false); setControlsVisible(true) }
                    MediaPlayer.Event.Stopped -> { setPlayIcon(false); setControlsVisible(true) }
                    MediaPlayer.Event.EndReached -> onEndReached()
                    MediaPlayer.Event.LengthChanged -> {
                        b.durationText.text = formatTime(e.lengthChanged)
                        b.seekBar.durationMs = e.lengthChanged
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        if (!dragging) b.timeText.text = formatTime(e.timeChanged)
                        if (abB > 0L && e.timeChanged >= abB) player.time = abA
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSave > 5000L) {
                            lastSave = now
                            saveProgress()
                        }
                    }
                    MediaPlayer.Event.PositionChanged ->
                        if (!dragging) b.seekBar.updateProgress(e.positionChanged)
                }
            }
        })
    }

    // ---------- Options panel (frosted) ----------

    private fun showPanel(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        b.optionsList.removeAllViews()
        for (i in labels.indices) {
            val tv = TextView(this)
            tv.text = (if (i == selected) "✓  " else "     ") + labels[i]
            tv.setTextColor(Color.WHITE)
            tv.textSize = 15f
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            if (i == selected) tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()
            )
            tv.setOnClickListener {
                tick(it)
                hidePanel()
                onPick(i)
            }
            b.optionsList.addView(tv)
        }
        val heightDp = minOf(labels.size * 48 + 16, 320)
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
    }

    private fun showSpeedPanel() {
        val labels = speeds.map { s ->
            (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "x"
        }
        showPanel(labels, speedIndex) { i ->
            speedIndex = i
            applySpeed()
        }
    }

    private fun showSubtitlePanel() {
        val labels = ArrayList<String>()
        val ids = ArrayList<Int>()
        labels.add("Off")
        ids.add(-1)
        val tracks = player.spuTracks
        if (tracks != null) {
            for (t in tracks) {
                if (t.id != -1) {
                    labels.add(t.name ?: ("Track " + t.id))
                    ids.add(t.id)
                }
            }
        }
        labels.add("Add subtitle file…")
        ids.add(-2)
        val selected = ids.indexOf(player.getSpuTrack()).coerceAtLeast(0)
        showPanel(labels, selected) { i ->
            val id = ids[i]
            if (id == -2) subtitlePicker.launch(arrayOf("*/*"))
            else player.setSpuTrack(id)
        }
    }

    private fun showAudioPanel() {
        val labels = ArrayList<String>()
        val ids = ArrayList<Int>()
        val tracks = player.audioTracks
        if (tracks != null) {
            for (t in tracks) {
                labels.add(if (t.id == -1) "Off" else (t.name ?: ("Track " + t.id)))
                ids.add(t.id)
            }
        }
        if (labels.isEmpty()) {
            toast("No audio tracks yet")
            return
        }
        val selected = ids.indexOf(player.getAudioTrack()).coerceAtLeast(0)
        showPanel(labels, selected) { i -> player.setAudioTrack(ids[i]) }
    }

    private fun addSubtitle(uri: Uri) {
        try {
            val raw = uri.lastPathSegment ?: "subtitle.srt"
            val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(cacheDir, "sub_$safe")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
            player.addSlave(0, Uri.fromFile(file), true) // 0 = subtitle
        } catch (e: Exception) {
            toast("Can't load this subtitle file")
        }
    }

    private fun showMorePanel() {
        val labels = arrayListOf(
            "Aspect ratio", "Sleep timer", "A-B repeat", "Equalizer",
            "Picture-in-picture", "Lock screen", "Video info", "Share"
        )
        showPanel(labels, -1) { i ->
            when (i) {
                0 -> showAspectPanel()
                1 -> showSleepPanel()
                2 -> showAbPanel()
                3 -> showEqPanel()
                4 -> enterPip()
                5 -> setLocked(true)
                6 -> {
                    val item = currentItem()
                    if (item != null) showInfo(item) else toast("No info for this video")
                }
                else -> {
                    val item = currentItem()
                    if (item != null) shareVideo(item) else toast("Nothing to share")
                }
            }
        }
    }

    private fun aspectType(i: Int): MediaPlayer.ScaleType = when (i) {
        1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        2 -> MediaPlayer.ScaleType.SURFACE_FILL
        3 -> MediaPlayer.ScaleType.SURFACE_16_9
        4 -> MediaPlayer.ScaleType.SURFACE_4_3
        5 -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
        else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    private fun showAspectPanel() {
        showPanel(aspectNames, aspectIndex) { i ->
            aspectIndex = i
            player.setVideoScale(aspectType(i))
        }
    }

    private fun showSleepPanel() {
        showPanel(sleepLabels, sleepIndex) { i ->
            sleepIndex = i
            sleepHandler.removeCallbacksAndMessages(null)
            if (sleepMinutes[i] > 0) {
                sleepHandler.postDelayed({
                    player.pause()
                    sleepIndex = 0
                    toast("Sleep timer: paused")
                }, sleepMinutes[i] * 60_000L)
                toast("Will pause in " + sleepMinutes[i] + " minutes")
            } else {
                toast("Sleep timer off")
            }
        }
    }

    private fun showAbPanel() {
        val labels = ArrayList<String>()
        if (abA < 0L) {
            labels.add("Set A at " + formatTime(player.time))
        } else if (abB < 0L) {
            labels.add("Set B at " + formatTime(player.time))
            labels.add("Clear")
        } else {
            labels.add("Clear A-B  (" + formatTime(abA) + " to " + formatTime(abB) + ")")
        }
        showPanel(labels, -1) { i ->
            if (abA < 0L) {
                abA = player.time
                toast("A set. Open this again to set B")
            } else if (abB < 0L && i == 0) {
                val t = player.time
                if (t > abA) {
                    abB = t
                    toast("Repeating A-B")
                } else {
                    toast("B must be after A")
                }
            } else {
                abA = -1L
                abB = -1L
                toast("A-B repeat cleared")
            }
        }
    }

    private fun showEqPanel() {
        val labels = ArrayList<String>()
        labels.add("Off")
        val count = MediaPlayer.Equalizer.getPresetCount()
        for (i in 0 until count) {
            labels.add(MediaPlayer.Equalizer.getPresetName(i) ?: ("Preset " + i))
        }
        showPanel(labels, eqIndex) { i ->
            eqIndex = i
            if (i == 0) player.setEqualizer(null)
            else player.setEqualizer(MediaPlayer.Equalizer.createFromPreset(i - 1))
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && hasMedia && !inLibrary) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            } catch (e: Exception) {
                toast("Picture-in-picture isn't available")
            }
        } else {
            toast("Picture-in-picture needs Android 8 or newer")
        }
    }

    // ---------- Cast ----------

    private fun startCastDiscovery() {
        if (discoverer != null) return
        try {
            val d = RendererDiscoverer(libVLC, "microdns_renderer")
            d.setEventListener(RendererDiscoverer.EventListener { ev ->
                runOnUiThread {
                    val item = ev.item
                    if (ev.type == RendererDiscoverer.Event.ItemAdded) {
                        item.retain()
                        renderers.add(item)
                    } else if (ev.type == RendererDiscoverer.Event.ItemDeleted) {
                        renderers.removeAll { it.name == item.name }
                    }
                }
            })
            d.start()
            discoverer = d
        } catch (e: Exception) {
            toast("Cast isn't available on this device")
        }
    }

    private fun showCastPanel() {
        startCastDiscovery()
        val labels = ArrayList<String>()
        labels.add("This phone")
        for (r in renderers) labels.add(r.displayName ?: r.name)
        if (renderers.isEmpty()) {
            Toast.makeText(
                this,
                "Searching for cast devices on your Wi-Fi… tap again in a few seconds",
                Toast.LENGTH_LONG
            ).show()
        }
        showPanel(labels, castIndex.coerceIn(0, labels.size - 1)) { i ->
            castTo(if (i == 0) null else renderers[i - 1], i)
        }
    }

    private fun castTo(item: RendererItem?, index: Int) {
        castIndex = index
        val t = player.time
        player.stop()
        player.setRenderer(item)
        player.play()
        handler.postDelayed({ player.time = t }, 1200)
    }

    // ---------- Frame preview (while dragging the progress bar) ----------

    private fun positionPreview() {
        val sb = IntArray(2)
        val ct = IntArray(2)
        b.seekBar.getLocationOnScreen(sb)
        b.controls.getLocationOnScreen(ct)
        val cardW = 136f * density
        val cardH = 77f * density
        var x = (sb[0] - ct[0]) + b.seekBar.thumbCenterX() - cardW / 2f
        x = x.coerceIn(8f * density, maxOf(b.controls.width - cardW - 8f * density, 8f * density))
        b.previewCard.translationX = x
        b.previewCard.translationY = (sb[1] - ct[1]) - cardH - 4f * density
    }

    // ---------- Frosted glass ----------

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
        val scale = 16
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
            if (!v.isShown) continue
            v.getLocationOnScreen(loc)
            m.setScale(sx, sy)
            m.postTranslate((tvLoc[0] - loc[0]).toFloat(), (tvLoc[1] - loc[1]).toFloat())
            v.setImageBitmap(bmp)
            v.imageMatrix = m
        }
    }

    private fun startBlur() {
        stopBlur()
        Choreographer.getInstance().postFrameCallback(blurTick)
    }

    private fun stopBlur() {
        Choreographer.getInstance().removeFrameCallback(blurTick)
        handler.removeCallbacks(blurPost)
    }

    private fun setControlsVisible(show: Boolean) {
        if (show && locked) return
        controlsVisible = show
        handler.removeCallbacks(hideRunnable)
        if (show) {
            b.controls.visibility = View.VISIBLE
            ViewCompat.requestApplyInsets(b.controls)
            b.controls.animate().alpha(1f).setDuration(200).start()
            startBlur()
            scheduleHide()
        } else {
            stopBlur()
            if (panelVisible) hidePanel()
            b.controls.animate().alpha(0f).setDuration(250).withEndAction {
                if (!controlsVisible) b.controls.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (player.isPlaying && controlsVisible && !panelVisible) {
            handler.postDelayed(hideRunnable, 3500)
        }
    }

    // ---------- Lifecycle ----------

    override fun onConfigurationChanged(newConfig: Configuration) {
        // animate the layout moving to its new orientation
        val t1 = AutoTransition()
        t1.duration = 300
        TransitionManager.beginDelayedTransition(b.libraryLayout, t1)
        val t2 = AutoTransition()
        t2.duration = 300
        TransitionManager.beginDelayedTransition(b.controls, t2)
        super.onConfigurationChanged(newConfig)
        grid.spanCount = computeSpan()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (hasMedia && !inLibrary && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hidePanel()
            setControlsVisible(false)
        }
    }

    override fun onStart() {
        super.onStart()
        textureView = null
        player.attachViews(b.videoLayout, null, true, true)
        if (inLibrary && allVideos.isEmpty() && hasPermission()) loadVideos()
    }

    override fun onStop() {
        super.onStop()
        stopBlur()
        saveProgress()
        if (player.isPlaying) player.pause()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBlur()
        handler.removeCallbacksAndMessages(null)
        sleepHandler.removeCallbacksAndMessages(null)
        framePreview.release()
        discoverer?.stop()
        discoverer?.release()
        player.release()
        libVLC.release()
        pfd?.close()
    }
}
