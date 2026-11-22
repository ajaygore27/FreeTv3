package com.example.freelivetv

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.URL
import java.util.TreeMap
import kotlin.concurrent.thread

data class Channel(
    val name: String,
    val group: String,
    val url: String,
    val country: String = "",        // ISO code parsed from tvg-id, lowercase
    val userAgent: String? = null,   // required UA for streams that 403 without it
    val referer: String? = null      // required Referer for some streams
) {
    val label get() = if (group.isEmpty()) name else "$name  •  ${group.replace(";", ", ")}"
}

class MainActivity : AppCompatActivity() {

    // Full iptv-org index: every publicly available free channel worldwide.
    private val playlistUrl = "https://iptv-org.github.io/iptv/index.m3u"

    // Browser-like headers: many streams return 403 to the default ExoPlayer UA,
    // which shows up as "geo blocked". Real IP-based geo-blocking still needs a VPN.
    private val defaultUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var countrySpinner: Spinner
    private lateinit var prefs: SharedPreferences

    private var all = listOf<Channel>()
    private var visible = listOf<Channel>()
    private var groups: Map<String, List<Channel>> = emptyMap()
    private var currentGroup: String? = null
    private var showingGroups = true
    private var shownGroups = listOf<String>()
    private var shownChannels = listOf<Channel>()
    private var fullscreen = false

    // --- country filter + hidden channels (persisted) ---
    private var hiddenUrls = mutableSetOf<String>()   // channels the user removed
    private var selectedCountry = ""                  // "" = All countries
    private var spinnerCodes = listOf<String>()       // code aligned with spinner positions
    private var spinnerReady = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                fullscreen -> setFullscreen(false)
                currentGroup != null -> { currentGroup = null; search.setText(""); refresh() }
                else -> search.setText("")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backCallback)

        prefs = getSharedPreferences("freetv", MODE_PRIVATE)
        hiddenUrls = prefs.getStringSet("hidden", emptySet())!!.toMutableSet()
        selectedCountry = prefs.getString("country", "") ?: ""

        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        list = findViewById(R.id.channels)
        playerView = findViewById(R.id.player)
        countrySpinner = findViewById(R.id.country)

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.setFullscreenButtonClickListener { setFullscreen(it) }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@MainActivity,
                    "Channel offline, geo-blocked (try a VPN), or removed",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (showingGroups) {
                currentGroup = shownGroups[pos]
                refresh()
                list.setSelection(0)
            } else {
                play(shownChannels[pos])
            }
        }
        // Long-press a channel to hide (remove) it.
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (showingGroups) return@setOnItemLongClickListener false
            val ch = shownChannels[pos]
            AlertDialog.Builder(this)
                .setTitle("Remove channel?")
                .setMessage("""${ch.name}

This channel will be hidden from all lists. Long-press lists to manage.""")
                .setPositiveButton("Remove") { _, _ ->
                    hiddenUrls.add(ch.url)
                    prefs.edit().putStringSet("hidden", hiddenUrls).apply()
                    applyFilters()
                    Toast.makeText(this, "Channel removed", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        // Tap the status bar to restore removed channels.
        status.setOnClickListener {
            if (hiddenUrls.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Restore removed channels?")
                .setMessage("${hiddenUrls.size} channel(s) are hidden.")
                .setPositiveButton("Restore all") { _, _ ->
                    hiddenUrls.clear()
                    prefs.edit().putStringSet("hidden", hiddenUrls).apply()
                    applyFilters()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        countrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!spinnerReady) return
                val code = spinnerCodes.getOrElse(pos) { "" }
                if (code != selectedCountry) {
                    selectedCountry = code
                    prefs.edit().putString("country", code).apply()
                    currentGroup = null
                    applyFilters()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadChannels()
    }

    /** Build a MediaItem with the headers the stream expects. Fixes most 403 "blocked" streams. */
    private fun play(ch: Channel) {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = ch.userAgent ?: defaultUa
        ch.referer?.let { headers["Referer"] = it }
        // Media3 applies per-channel HTTP headers through the DataSource factory,
        // not through MediaItem.Builder.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(ch.url))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    private fun loadChannels() {
        val cache = File(cacheDir, "playlist.m3u")
        if (cache.exists()) setChannels(parseM3u(cache.readText()))
        thread {
            try {
                val text = URL(playlistUrl).readText()
                cache.writeText(text)
                val parsed = parseM3u(text)
                runOnUiThread { setChannels(parsed) }
            } catch (e: Exception) {
                runOnUiThread { if (all.isEmpty()) status.text = "Couldn't load channels. Check internet." }
            }
        }
    }

    private fun setChannels(channels: List<Channel>) {
        all = channels
        populateCountrySpinner()
        applyFilters()
    }

    /** Remove hidden channels, apply the country filter, rebuild groups. */
    private fun applyFilters() {
        visible = all.filter { it.url !in hiddenUrls }
        if (selectedCountry.isNotEmpty()) visible = visible.filter { it.country == selectedCountry }
        val map = TreeMap<String, MutableList<Channel>>(String.CASE_INSENSITIVE_ORDER)
        for (c in visible) {
            val gs = c.group.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("Uncategorized") }
            for (g in gs) map.getOrPut(g) { mutableListOf() }.add(c)
        }
        groups = map
        if (currentGroup != null && !map.containsKey(currentGroup)) currentGroup = null
        refresh()
    }

    /** Fill the country dropdown with only the countries present in the playlist. */
    private fun populateCountrySpinner() {
        val counts = TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        for (c in all) if (c.country.isNotEmpty()) counts.merge(c.country, 1, Int::plus)
        val codes = listOf("") + counts.keys.toList()          // "" = All countries
        val labels = codes.map { code ->
            if (code.isEmpty()) "All countries  (${all.size})"
            else "${CountryNames.name(code)}  (${counts[code]})"
        }
        spinnerCodes = codes
        spinnerReady = false
        val spAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        spAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        countrySpinner.adapter = spAdapter
        val sel = codes.indexOf(selectedCountry)
        countrySpinner.setSelection(if (sel >= 0) sel else 0)
        if (sel < 0) selectedCountry = ""
        spinnerReady = true
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        val g = currentGroup
        adapter.clear()
        if (g == null && q.isEmpty()) {
            showingGroups = true
            shownGroups = groups.keys.toList()
            adapter.addAll(shownGroups.map { "$it  (${groups[it]!!.size})" })
            val base = if (selectedCountry.isEmpty()) "All countries" else CountryNames.name(selectedCountry)
            val hiddenHint = if (hiddenUrls.isEmpty()) "" else "  •  ${hiddenUrls.size} hidden (tap to restore)"
            status.text = "$base • ${groups.size} groups • ${visible.size} channels$hiddenHint"
        } else {
            showingGroups = false
            val base = if (g == null) visible else groups[g].orEmpty()
            shownChannels = if (q.isEmpty()) base else base.filter {
                it.name.lowercase().contains(q) || (g == null && it.group.lowercase().contains(q))
            }
            adapter.addAll(shownChannels.map { if (g == null) it.label else it.name })
            status.text = "${g ?: "Search results"} • ${shownChannels.size} channels"
        }
        updateBack()
    }

    private fun updateBack() {
        backCallback.isEnabled = fullscreen || currentGroup != null || search.text.isNotEmpty()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        search.visibility = vis; status.visibility = vis; list.visibility = vis
        countrySpinner.visibility = vis
        playerView.layoutParams = playerView.layoutParams.apply {
            height = if (on) ViewGroup.LayoutParams.MATCH_PARENT
            else (220 * resources.displayMetrics.density).toInt()
        }
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        updateBack()
    }

    private val groupRegex = Regex("group-title=\"([^\"]*)\"")
    private val idRegex = Regex("tvg-id=\"([^\"]*)\"")
    private val uaAttrRegex = Regex("http-user-agent=\"([^\"]*)\"")
    private val refAttrRegex = Regex("http-referrer=\"([^\"]*)\"")
    private val countryRegex = Regex("""\.([a-z]{2})(?:@|$)""")

    private fun parseM3u(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        var id = ""
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(",").trim()
                    group = groupRegex.find(line)?.groupValues?.get(1).orEmpty()
                    id = idAttr(line)
                    ua = uaAttrRegex.find(line)?.groupValues?.get(1)
                    ref = refAttrRegex.find(line)?.groupValues?.get(1)
                }
                line.startsWith("#EXTVLCOPT:http-user-agent") ->
                    ua = line.substringAfter("=").trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:http-referrer") ->
                    ref = line.substringAfter("=").trim().ifEmpty { null }
                line.isNotEmpty() && !line.startsWith("#") && name != null -> {
                    val country = countryRegex.find(id)?.groupValues?.get(1).orEmpty()
                    out.add(Channel(name, group, line, country, ua, ref))
                    name = null; group = ""; id = ""; ua = null; ref = null
                }
            }
        }
        return out
    }

    private fun idAttr(line: String): String = idRegex.find(line)?.groupValues?.get(1).orEmpty()

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { super.onDestroy(); player.release() }
}
