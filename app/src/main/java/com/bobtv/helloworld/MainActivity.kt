package com.bobtv.helloworld

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.bobtv.helloworld.data.*
import com.bobtv.helloworld.diagnostics.SessionLog
import com.bobtv.helloworld.network.BoundedHttpSession
import com.bobtv.helloworld.player.TvPlayer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val health = AvailabilityState()
    private val recovery = PlaybackRecovery()
    private lateinit var repository: PlaylistRepository
    private lateinit var scanner: AvailabilityScanner
    private lateinit var screen: TvScreen
    private lateinit var tvPlayer: TvPlayer
    private lateinit var log: SessionLog
    private var channels = emptyList<Channel>()
    private var currentChannel: Channel? = null
    private var playingSource: SourceKey? = null
    private var started = false
    private var destroyed = false
    private var initialLoading = false
    private var refreshing = false
    private var scanning = false
    private var autoStartPending = true
    private var preferredId: String? = null
    private var ioGeneration = 0L
    private var scanGeneration = 0L
    private var initialJob: Future<*>? = null
    private var refreshJob: Future<*>? = null
    private var catalogueDescription = ""
    private val preferences by lazy { getSharedPreferences("bobtv", MODE_PRIVATE) }
    private val periodicRefresh = object : Runnable {
        override fun run() {
            if (started) {
                refreshCatalogue()
                handler.postDelayed(this, PlaylistRepository.REFRESH_INTERVAL_MS)
            }
        }
    }
    private val periodicProbe = Runnable { checkAvailability() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        log = SessionLog("BOBTV " + version + " · " + packageName + "\nDevice: " +
            Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE +
            " / API " + Build.VERSION.SDK_INT) { level, tag, message ->
            Log.println(when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; else -> Log.INFO }, "BOBTV." + tag, message)
        }
        log.info("Main", "Session started")
        repository = PlaylistRepository(File(filesDir, "canales.m3u"),
            { assets.open("canales.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() } },
            log = { log.info("Catalogue.HTTP", it) })
        scanner = AvailabilityScanner(createProbe = { key ->
            HlsSourceProbe(key.url, Media3ManifestReader(),
                BoundedHttpSession(HlsSourceProbe.DEADLINE_MS, log = { message ->
                    log.info("Probe.HTTP", "channel=" + key.channelId + " source=" + key.url + " · " + message)
                }))
        })
        screen = TvScreen(this, ::selectChannel, ::refreshCatalogue, ::acknowledgeFailure, ::exportLogs, ::finish)
        tvPlayer = TvPlayer(this, findViewById(R.id.player_view), log, ::onPlaybackEvent)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        started = true
        preferredId = currentChannel?.id ?: preferences.getString("last_channel", null)
        autoStartPending = !recovery.awaitingAcknowledgement
        if (autoStartPending) screen.setPlaybackStatus(TvPlayer.Status.LOADING)
        playingSource = null
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        log.info("Main", "Foreground · network available=" + (capabilities != null) +
            " validated=" + (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
        if (channels.isEmpty()) loadInitialCatalogue() else {
            health.setCatalogue(channels, reset = true)
            checkAvailability()
            refreshCatalogue()
        }
        handler.postDelayed(periodicRefresh, PlaylistRepository.REFRESH_INTERVAL_MS)
    }

    private fun loadInitialCatalogue() {
        if (initialLoading || !started || destroyed) return
        initialLoading = true
        screen.setAvailability(checking = true)
        val token = ioGeneration
        initialJob = io.submit {
            val result = runCatching { repository.loadInitial() }
            handler.post {
                if (!started || destroyed || token != ioGeneration) return@post
                initialLoading = false
                result.onSuccess { catalogue ->
                    channels = catalogue.channels
                    health.setCatalogue(channels, reset = true)
                    catalogueDescription = getString(if (catalogue.fromCache) R.string.saved_catalogue else R.string.initial_catalogue)
                    log.info("Catalogue", catalogueDescription + " · channels=" + channels.size)
                    checkAvailability()
                    refreshCatalogue()
                }.onFailure { error ->
                    log.error("Catalogue", "Unable to load cached or bundled playlist", error)
                    autoStartPending = false
                    screen.setAvailability(checking = false)
                    screen.showEmptyChannels()
                }
            }
        }
    }

    private fun refreshCatalogue() {
        if (refreshing || initialLoading || !started || destroyed) return
        if (channels.isEmpty()) { loadInitialCatalogue(); return }
        refreshing = true
        updateAvailabilityUi()
        val token = ioGeneration
        log.info("Catalogue", "Refreshing " + PlaylistRepository.PLAYLIST_URL)
        refreshJob = io.submit {
            val start = System.nanoTime()
            val result = repository.refresh()
            handler.post {
                if (!started || destroyed || token != ioGeneration) return@post
                refreshing = false
                val elapsed = (System.nanoTime() - start) / 1_000_000
                when (result) {
                    is PlaylistRepository.RefreshResult.Updated -> {
                        channels = result.channels
                        health.setCatalogue(channels)
                        currentChannel?.id?.let { id ->
                            channels.firstOrNull { it.id == id }?.let { updated ->
                                currentChannel = updated
                                tvPlayer.updateSources(updated)
                            }
                        }
                        catalogueDescription = getString(R.string.updated_catalogue)
                        log.info("Catalogue", "Updated channels=" + channels.size + " · " + elapsed + " ms")
                    }
                    is PlaylistRepository.RefreshResult.Kept -> {
                        catalogueDescription = getString(R.string.catalogue_kept)
                        log.warn("Catalogue", "Keeping previous catalogue · " + elapsed + " ms", result.error)
                    }
                }
                checkAvailability()
            }
        }
    }

    private fun checkAvailability() {
        if (!started || destroyed || channels.isEmpty()) return
        handler.removeCallbacks(periodicProbe)
        val token = ++scanGeneration
        scanning = true
        val tickets = health.beginScan(channels, preferredId, playingSource)
        updateAvailabilityUi()
        scanner.scan(tickets, { ticket, result ->
            handler.post {
                if (!started || destroyed || token != scanGeneration) return@post
                val accepted = health.apply(ticket, result)
                val detail = "channel=" + ticket.key.channelId + " source=" + ticket.key.url +
                    " online=" + result.online + " · " + result.elapsedMs + " ms · " + result.detail +
                    if (accepted) "" else " · stale result ignored"
                if (result.online) log.info("Probe", detail) else log.warn("Probe", detail, result.error)
                updateAvailabilityUi()
                maybeStartPlayback()
            }
        }, {
            handler.post {
                if (!started || destroyed || token != scanGeneration) return@post
                scanning = false
                health.cancelScan()
                updateAvailabilityUi()
                maybeStartPlayback()
                if (currentChannel == null && availableChannels().isEmpty()) screen.showEmptyChannels()
                handler.postDelayed(periodicProbe, AvailabilityScanner.INTERVAL_MS)
            }
        })
    }

    private fun availableChannels(): List<Channel> = health.available(channels)
        .filter { it.id != recovery.failedChannelId }

    private fun updateAvailabilityUi() {
        val available = availableChannels()
        screen.setCatalogue(channels, available)
        screen.setAvailability(scanning || initialLoading)
        val count = resources.getQuantityString(R.plurals.active_channels, available.size, available.size)
        val progress = if (scanning) resources.getQuantityString(R.plurals.probe_progress,
            health.pendingCount, health.pendingCount) else ""
        screen.setCatalogueStatus(listOf(catalogueDescription, count, progress).filter(String::isNotBlank).joinToString(" · "), refreshing)
    }

    private fun maybeStartPlayback() {
        if (!autoStartPending || !started || recovery.awaitingAcknowledgement) return
        val available = availableChannels()
        val preferred = channels.firstOrNull { it.id == preferredId }
        if (preferred != null && health.onlineSources(preferred).isEmpty() && health.isPending(preferred)) return
        ChannelSelection.restore(available, preferredId)?.let(::selectChannel)
    }

    private fun selectChannel(channel: Channel) {
        if (!started) return
        val selected = channels.firstOrNull { it.id == channel.id } ?: return
        val source = health.onlineSources(selected).firstOrNull() ?: return
        autoStartPending = false
        recovery.clear()
        currentChannel = selected
        preferredId = selected.id
        playingSource = null
        preferences.edit().putString("last_channel", selected.id).apply()
        log.info("Main", "Selected channel=" + selected.id + " source=" + source)
        screen.setCurrentChannel(selected)
        tvPlayer.play(selected, source)
    }

    private fun onPlaybackEvent(event: TvPlayer.Event) {
        if (!started || currentChannel?.id != event.channelId) return
        val key = SourceKey(event.channelId, event.source)
        if (event.sourceFailed) { health.recordPlayback(key, false); playingSource = null }
        if (event.status == TvPlayer.Status.PLAYING) {
            health.recordPlayback(key, true)
            playingSource = key
        }
        if (event.status == TvPlayer.Status.FAILED) {
            currentChannel?.let(health::markChannelOffline)
            recovery.fail(event.channelId)
            autoStartPending = false
            playingSource = null
            log.error("Main", "Awaiting acknowledgement before changing channel=" + event.channelId + " · " + event.reason)
        }
        screen.setPlaybackStatus(event.status)
        updateAvailabilityUi()
        if (event.status == TvPlayer.Status.FAILED) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun acknowledgeFailure() {
        if (!recovery.awaitingAcknowledgement) return
        log.info("Main", "User acknowledged failed channel=" + recovery.failedChannelId)
        val next = recovery.acknowledge(channels, availableChannels())
        if (next != null) selectChannel(next) else {
            log.warn("Main", "No alternative channel currently online")
            currentChannel = null
            playingSource = null
            tvPlayer.release()
            screen.showEmptyChannels()
            updateAvailabilityUi()
        }
    }

    private fun exportLogs() {
        log.info("Main", "Exporting session logs to clipboard")
        try {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BOBTV logs", log.export()))
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            log.error("Main", "Clipboard export failed", error)
            Toast.makeText(this, R.string.logs_copy_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (::screen.isInitialized && screen.handleKeyEvent(event)) true else super.dispatchKeyEvent(event)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { screen.requestBack() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onStop() {
        started = false
        ioGeneration++
        scanGeneration++
        handler.removeCallbacksAndMessages(null)
        initialJob?.cancel(true)
        refreshJob?.cancel(true)
        repository.cancel()
        scanner.cancel()
        health.cancelScan()
        initialLoading = false
        refreshing = false
        scanning = false
        playingSource = null
        tvPlayer.release()
        screen.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log.info("Main", "Background: playback and checks stopped")
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        scanner.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
