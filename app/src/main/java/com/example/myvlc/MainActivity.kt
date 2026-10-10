package com.example.myvlc

import android.Manifest
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.example.myvlc.databinding.ActivityMainBinding
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.RendererItem
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var adapter: LibraryAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var blurViews: List<ImageView>
    private var pfd: ParcelFileDescriptor? = null

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

    private val handler = Handler(Looper.getMainLooper())
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

    // library / playlist
    private var allSeries: Map<String, List<VideoItem>> = emptyMap()
    private var looseVideos: List<VideoItem> = emptyList()
    private var currentSeries: String? = null
    private var currentPlaylist: String? = null
    private lateinit var grid: GridLayoutManager
    private var tab = 0
    private var query = ""
    private var searchVisible = false
    private lateinit var store: PlaylistStore
    private var allVideos: List<VideoItem> = emptyList()
    private var byUri: Map<String, VideoItem> = emptyMap()
    private var playlist: List<VideoItem> = emptyList()
    private var playIndex = -1

    // playback options
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2

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

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { addSubtitle(it) }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (panelVisible) hidePanel()
            else if (!inLibrary) showLibrary()
            else if (currentSeries != null || currentPlaylist != null) libraryUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("prism", MODE_PRIVATE)
        store = PlaylistStore(prefs)
        themeIndex = prefs.getInt("theme", 0).coerceIn(0, themeStyles.size - 1)
        setTheme(themeStyles[themeIndex])
        if (themeIndex == 0) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        blurViews = listOf(
            b.blurPrev, b.blurPlay, b.blurNext, b.blurPill, b.blurNextPill, b.blurPanel,
            b.blurBottom
        )

        // Keep the title clear of the selfie camera / notch (and side cutouts in landscape)
        val density = resources.displayMetrics.density
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

        libVLC = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        player = MediaPlayer(libVLC)

        ViewCompat.setOnApplyWindowInsetsListener(b.libraryLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        adapter = LibraryAdapter(
            onSeries = { name -> openSeries(name) },
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

            // Detect series: same show name with episode numbers (2+ files)
            val parsed = list.map { item -> Pair(item, parseEpisode(item.title)) }
            val groups = parsed.filter { it.second != null }.groupBy { it.second!!.name.lowercase() }
            val seriesMap = LinkedHashMap<String, List<VideoItem>>()
            val used = HashSet<Uri>()
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
                    g.forEach { used.add(it.first.uri) }
                }
            }
            val loose = list.filter { it.uri !in used }
            val sorted = seriesMap.toSortedMap(String.CASE_INSENSITIVE_ORDER)

            runOnUiThread {
                allSeries = sorted
                looseVideos = loose
                allVideos = list
                byUri = (loose + sorted.values.flatten()).associateBy { it.uri.toString() }
                if (currentSeries != null && !sorted.containsKey(currentSeries)) currentSeries = null
                if (currentPlaylist != null && !store.names().contains(currentPlaylist)) currentPlaylist = null
                applyLibraryHeader()
                rebuildRows()
                if (list.isEmpty()) showEmpty(false)
            }
        }.start()
    }

    private fun playlistVideos(name: String): List<VideoItem> =
        store.uris(name).mapNotNull { byUri[it] }

    private fun rebuildRows() {
        val rows = ArrayList<Row>()
        val cs = currentSeries
        val cp = currentPlaylist
        var listMode = false
        if (cp != null) {
            listMode = true
            rows.add(Row.AddVideos)
            playlistVideos(cp).forEach { rows.add(Row.VideoRow(it)) }
        } else if (cs != null) {
            listMode = true
            allSeries[cs]?.forEach { rows.add(Row.VideoRow(it)) }
        } else if (tab == 1) {
            if (allSeries.isEmpty()) {
                rows.add(Row.Note("No series found yet.\nName files like  Show S01E01  and they will appear here."))
            } else {
                for ((name, eps) in allSeries) {
                    rows.add(Row.SeriesRow(name, eps.size, eps.first().uri))
                }
            }
        } else if (tab == 2) {
            rows.add(Row.NewPlaylist)
            for (name in store.names()) {
                val vids = playlistVideos(name)
                rows.add(Row.PlaylistRow(name, vids.size, vids.firstOrNull()?.uri))
            }
        } else {
            val q = query.lowercase()
            val vids = allVideos
                .map { byUri[it.uri.toString()] ?: it }
                .filter { q.isEmpty() || it.title.lowercase().contains(q) }
            if (vids.isEmpty() && allVideos.isNotEmpty()) {
                rows.add(Row.Note("No videos match your search"))
            } else {
                vids.forEach { rows.add(Row.VideoRow(it)) }
            }
        }
        adapter.submit(rows, listMode)
    }

    private fun applyLibraryHeader() {
        val title = currentSeries ?: currentPlaylist
        val detail = title != null
        if (detail) closeSearch()
        b.libTitle.text = title ?: "Prism"
        b.libBack.visibility = if (detail) View.VISIBLE else View.GONE
        b.libLogo.visibility = if (detail) View.GONE else View.VISIBLE
        b.tabGroup.visibility = if (detail) View.GONE else View.VISIBLE
        b.searchBtn.visibility = if (detail) View.GONE else View.VISIBLE
        b.searchBox.visibility = if (!detail && searchVisible) View.VISIBLE else View.GONE
        if (inLibrary) backCallback.isEnabled = detail
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
        return (widthDp / 170f).toInt().coerceIn(2, 6)
    }

    private fun refreshLibrary() {
        applyLibraryHeader()
        rebuildRows()
        b.videoList.scrollToPosition(0)
    }

    private fun refreshKeepScroll() {
        applyLibraryHeader()
        rebuildRows()
    }

    private fun openSeries(name: String?) {
        currentSeries = name
        currentPlaylist = null
        refreshLibrary()
    }

    private fun openPlaylist(name: String?) {
        currentPlaylist = name
        currentSeries = null
        refreshLibrary()
    }

    private fun libraryUp() {
        currentSeries = null
        currentPlaylist = null
        refreshLibrary()
    }

    private fun onVideoClicked(item: VideoItem) {
        val cp = currentPlaylist
        val cs = currentSeries
        val itemSeries = item.series
        if (cp != null) {
            val vids = playlistVideos(cp)
            startPlaylist(vids, vids.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0))
        } else if (cs != null) {
            val eps = allSeries[cs] ?: listOf(item)
            startPlaylist(eps, eps.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0))
        } else if (itemSeries != null) {
            val eps = allSeries[itemSeries] ?: listOf(item)
            startPlaylist(eps, eps.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0))
        } else {
            startPlaylist(listOf(item), 0)
        }
    }

    // ---------- Your playlists ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun askName(title: String, initial: String, positive: String, onDone: (String) -> Unit) {
        val dp = resources.displayMetrics.density
        val input = EditText(this)
        input.hint = "Playlist name"
        input.setSingleLine()
        input.setText(initial)
        input.setSelection(input.text.length)
        val box = FrameLayout(this)
        val pad = (20 * dp).toInt()
        box.setPadding(pad, (8 * dp).toInt(), pad, 0)
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
        val options = if (cp != null) arrayOf("Remove from this playlist", "Add to another playlist")
        else arrayOf("Add to playlist")
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                if (cp != null && which == 0) {
                    store.remove(cp, item.uri.toString())
                    refreshKeepScroll()
                } else {
                    showAddToPlaylist(item)
                }
            }
            .show()
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

    private fun isDarkUi(): Boolean {
        if (themeIndex != 0) return true
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun showLibrary() {
        inLibrary = true
        stopBlur()
        handler.removeCallbacksAndMessages(null)
        hidePanel()
        if (hasMedia) player.stop()
        player.setRenderer(null)
        castIndex = 0
        hasMedia = false
        controlsVisible = false
        b.controls.visibility = View.GONE
        b.libraryLayout.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyLibraryHeader()

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
        b.libraryLayout.visibility = View.GONE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setControlsVisible(true)
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

    // ---------- Playlist ----------

    private fun startPlaylist(list: List<VideoItem>, index: Int) {
        playlist = list
        playIndex = index
        playCurrent()
    }

    private fun playCurrent() {
        val item = playlist.getOrNull(playIndex) ?: return
        b.titleText.text = item.title
        if (item.series != null) {
            b.subtitleText.text = String.format("%s · S%02dE%02d", item.series, item.season, item.episode)
            b.subtitleText.visibility = View.VISIBLE
        } else {
            b.subtitleText.visibility = View.GONE
        }
        updateNavButtons()
        startMedia(item.uri)
    }

    private fun playExternal(uri: Uri) {
        playlist = emptyList()
        playIndex = -1
        b.titleText.text = uri.lastPathSegment ?: "Video"
        b.subtitleText.visibility = View.GONE
        updateNavButtons()
        startMedia(uri)
    }

    private fun hasNext() = playIndex in 0 until playlist.size - 1

    private fun playNext() {
        if (hasNext()) {
            playIndex++
            playCurrent()
        }
    }

    private fun playPrev() {
        if (playIndex > 0) {
            playIndex--
            playCurrent()
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

    // ---------- UI ----------

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

        b.themeBtn.setOnClickListener { showThemeDialog() }
        b.searchBtn.setOnClickListener { toggleSearch() }
        b.searchBox.doAfterTextChanged { text ->
            query = (text?.toString() ?: "").trim()
            if (query.isNotEmpty() && tab != 0) b.chipVideos.isChecked = true
            else refreshKeepScroll()
        }
        b.searchBox.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        b.tabGroup.setOnCheckedStateChangeListener { _, ids ->
            tab = when (ids.firstOrNull()) {
                R.id.chipSeries -> 1
                R.id.chipPlaylists -> 2
                else -> 0
            }
            refreshLibrary()
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

        b.backBtn.setOnClickListener { showLibrary() }
        b.prevBtn.setOnClickListener { playPrev() }
        b.nextBtn.setOnClickListener { playNext() }
        b.nextPill.setOnClickListener { playNext() }
        b.ccBtn.setOnClickListener { showSubtitlePanel() }
        b.audioBtn.setOnClickListener { showAudioPanel() }
        b.castBtn.setOnClickListener { showCastPanel() }
        b.speedBtn.setOnClickListener { showSpeedPanel() }
        b.rotateBtn.setOnClickListener { toggleOrientation() }

        b.playPause.setOnClickListener {
            b.playPause.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                b.playPause.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            if (player.isPlaying) player.pause() else player.play()
        }

        b.seekBar.onSeekStart = {
            dragging = true
            handler.removeCallbacks(hideRunnable)
        }
        b.seekBar.onSeekPreview = { p ->
            b.timeText.text = formatTime((p * maxOf(player.length, 0L)).toLong())
        }
        b.seekBar.onSeekEnd = { p ->
            player.position = p
            dragging = false
            scheduleHide()
        }

        // Tap = show/hide controls, double tap left/right = seek -/+10s
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (panelVisible) hidePanel()
                else if (hasMedia) setControlsVisible(!controlsVisible)
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
                        setPlayIcon(true)
                        scheduleHide()
                        if (controlsVisible) startBlur()
                    }
                    MediaPlayer.Event.Paused -> { setPlayIcon(false); setControlsVisible(true) }
                    MediaPlayer.Event.Stopped -> { setPlayIcon(false); setControlsVisible(true) }
                    MediaPlayer.Event.EndReached -> {
                        if (hasNext()) playNext()
                        else { setPlayIcon(false); setControlsVisible(true) }
                    }
                    MediaPlayer.Event.LengthChanged -> {
                        b.durationText.text = formatTime(e.lengthChanged)
                        b.seekBar.durationMs = e.lengthChanged
                    }
                    MediaPlayer.Event.TimeChanged ->
                        if (!dragging) b.timeText.text = formatTime(e.timeChanged)
                    MediaPlayer.Event.PositionChanged ->
                        if (!dragging) b.seekBar.updateProgress(e.positionChanged)
                }
            }
        })
    }

    private fun startMedia(uri: Uri) {
        try {
            val old = pfd
            val fd = contentResolver.openFileDescriptor(uri, "r")
            pfd = fd
            val media = Media(libVLC, fd!!.fileDescriptor)
            player.media = media
            media.release()
            old?.close()
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

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ---------- Options panel (frosted) ----------

    private fun showPanel(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val dp = resources.displayMetrics.density
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
            tv.setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()
            )
            tv.setOnClickListener {
                hidePanel()
                onPick(i)
            }
            b.optionsList.addView(tv)
        }
        val heightDp = minOf(labels.size * 48 + 16, 320)
        val lp = b.optionsPanel.layoutParams
        lp.height = (heightDp * dp).toInt()
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
            Toast.makeText(this, "No audio tracks yet", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Can't load this subtitle file", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Cast isn't available on this device", Toast.LENGTH_SHORT).show()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        grid.spanCount = computeSpan()
    }

    override fun onStart() {
        super.onStart()
        textureView = null
        player.attachViews(b.videoLayout, null, true, true)
        if (inLibrary && allSeries.isEmpty() && looseVideos.isEmpty() && hasPermission()) loadVideos()
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
        discoverer?.stop()
        discoverer?.release()
        player.release()
        libVLC.release()
        pfd?.close()
    }
}
