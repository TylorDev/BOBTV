package com.bobtv.helloworld

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.bobtv.helloworld.data.*
import com.bobtv.helloworld.diagnostics.SessionLog
import com.bobtv.helloworld.diagnostics.ResourceMonitor
import com.bobtv.helloworld.network.NetworkMonitor
import com.bobtv.helloworld.network.NetworkState
import com.bobtv.helloworld.player.FailureCategory
import com.bobtv.helloworld.network.BoundedHttpSession
import com.bobtv.helloworld.player.TvPlayer
import com.bobtv.helloworld.audio.AndroidDeviceAudio
import com.bobtv.helloworld.audio.StoredVolumePreferences
import com.bobtv.helloworld.audio.VolumeController
import com.bobtv.helloworld.updates.UpdateCoordinator
import com.bobtv.helloworld.updates.UpdateServices
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    private lateinit var volume: VolumeController
    private lateinit var updates: UpdateCoordinator
    private var lastUpdateMessage = ""
    private lateinit var log: SessionLog
    private lateinit var resourceMonitor: ResourceMonitor
    private lateinit var network: NetworkMonitor
    private var networkState = NetworkState(false, false, 0)
    private var eventGeneration = 0L
    private var eventAttempt = 0L
    private val remoteDelays = java.util.ArrayDeque<Long>()
    private var remoteMax = 0L
    private var remoteEvents = 0L
    private var lastRemoteSummary = 0L
    private var channels = emptyList<Channel>()
    private var currentChannel: Channel? = null
    private var playingSource: SourceKey? = null
    private var started = false
    private var destroyed = false
    private var initialLoading = false
    private var refreshing = false
    @Volatile private var savingLogs = false
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
        log.info("Main", "Session started · ABI=" + Build.SUPPORTED_ABIS.joinToString() +
            " · process64bit=" + android.os.Process.is64Bit() + " · heapMax=" + Runtime.getRuntime().maxMemory())
        repository = PlaylistRepository(File(filesDir, "canales.m3u"),
            { assets.open("canales.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() } },
            log = { log.info("Catalogue.HTTP", it) })
        scanner = AvailabilityScanner(createProbe = { key ->
            HlsSourceProbe(key.url, Media3ManifestReader(),
                BoundedHttpSession(HlsSourceProbe.DEADLINE_MS, log = { message ->
                    log.info("Probe.HTTP", "channel=" + key.channelId + " source=" + key.url + " · " + message)
                }))
        })
        screen = TvScreen(this, ::selectChannel, { refreshCatalogue(manual = true) }, ::acknowledgeFailure,
            ::exportLogs, ::finish, ::retryCurrentChannel, ::saveLogsToDownloads, ::adjustVolume,
            { updates.checkNow() })
        tvPlayer = TvPlayer(this, findViewById(R.id.player_view), log, ::onPlaybackEvent)
        volume = VolumeController(AndroidDeviceAudio(getSystemService(AudioManager::class.java)) { log.info("Volume.Device", it) },
            StoredVolumePreferences(preferences), tvPlayer::setVolume) { log.info("Volume", it) }
        resourceMonitor = ResourceMonitor(this, log, tvPlayer::onResources)
        network = NetworkMonitor(this, ::onNetworkChanged)
        updates = UpdateCoordinator(this, UpdateServices.get(this), { status ->
            screen.setUpdateStatus(status)
            if (status.message != lastUpdateMessage) {
                lastUpdateMessage = status.message
                log.info("Update.Status", "${status.phase} · ${status.message}")
            }
        },
            ::stopPlayback, ::startPlayback) { log.info("Update", it) }
        updates.restoreState(savedInstanceState)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (!updates.start()) startPlayback()
    }

    private fun startPlayback() {
        if (started || destroyed) return
        started = true
        volume.start()
        preferredId = currentChannel?.id ?: preferences.getString("last_channel", null)
        autoStartPending = currentChannel == null
        if (autoStartPending) screen.setPlaybackStatus(TvPlayer.Status.LOADING)
        playingSource = null
        network.start()
        resourceMonitor.start()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        log.info("Main", "Foreground · network available=" + (capabilities != null) +
            " validated=" + (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
        if (channels.isEmpty()) loadInitialCatalogue() else {
            health.setCatalogue(channels)
            currentChannel?.let { tvPlayer.play(it, health.onlineSources(it).firstOrNull()) }
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
                    updateAvailabilityUi()
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

    private fun refreshCatalogue(manual: Boolean = false) {
        if (refreshing || initialLoading || !started || destroyed) return
        if (!manual && currentChannel != null && !tvPlayer.allowsBackgroundWork) return
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
                updateAvailabilityUi()
                checkAvailability()
            }
        }
    }

    private fun checkAvailability() {
        if (!started || destroyed || channels.isEmpty()) return
        handler.removeCallbacks(periodicProbe)
        if (scanning) return
        if (!networkState.usable || (currentChannel != null && !tvPlayer.allowsRecoveryProbe && !tvPlayer.allowsBackgroundWork)) {
            handler.postDelayed(periodicProbe, 15_000)
            return
        }
        val token = ++scanGeneration
        scanning = true
        val tickets = health.beginScan(channels, preferredId, playingSource)
        updateAvailabilityUi()
        scanner.scan(tickets, { ticket, result ->
            handler.post {
                if (!started || destroyed || token != scanGeneration) return@post
                val accepted = health.apply(ticket, result, networkState.usable &&
                    (networkState.validated || health.available(channels).isNotEmpty()))
                val detail = "channel=" + ticket.key.channelId + " source=" + ticket.key.url +
                    " verdict=" + result.verdict + " · " + result.elapsedMs + " ms · " + result.detail +
                    if (accepted) "" else " · stale result ignored"
                if (result.online) log.info("Probe", detail) else log.warn("Probe", detail, result.error)
                updateAvailabilityUi()
                maybeStartPlayback()
                if (accepted && result.online && currentChannel?.id == ticket.key.channelId && tvPlayer.isFailed)
                    tvPlayer.retry(manual = false)
            }
        }, {
            handler.post {
                if (!started || destroyed || token != scanGeneration) return@post
                scanning = false
                health.cancelScan()
                updateAvailabilityUi()
                maybeStartPlayback()
                if (currentChannel == null && availableChannels().isEmpty()) screen.showEmptyChannels()
                handler.postDelayed(periodicProbe, if (tvPlayer.isFailed) 60_000 else AvailabilityScanner.INTERVAL_MS)
            }
        })
    }

    private fun availableChannels(): List<Channel> = health.available(channels)

    private fun updateAvailabilityUi() {
        val available = availableChannels()
        screen.setCatalogue(channels, health.visible(channels), health.navigable(channels), channels.associate { it.id to health.label(it) })
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
        if (selected !in health.visible(channels)) return
        val source = health.onlineSources(selected).minByOrNull { tvPlayer.sourcePenalty(selected.id, it) } ?: selected.sources.first()
        autoStartPending = false
        pauseAvailabilityScan()
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
        if (event.generation < eventGeneration || (event.generation == eventGeneration && event.attempt < eventAttempt)) return
        eventGeneration = event.generation
        eventAttempt = event.attempt
        val key = SourceKey(event.channelId, event.source)
        health.recordStability(key, event.unstable)
        if (event.failure != null) { health.recordPlayback(key, false); playingSource = null }
        if (event.evidence) {
            health.recordPlayback(key, true)
            playingSource = key
            recovery.clear()
        }
        if (event.status == TvPlayer.Status.FAILED) {
            recovery.fail(event.channelId)
            autoStartPending = false
            playingSource = null
            log.error("Main", "Awaiting acknowledgement before changing channel=" + event.channelId + " · " + event.reason)
        }
        if (event.status != TvPlayer.Status.PLAYING) pauseAvailabilityScan()
        screen.setPlaybackStatus(event.status)
        val message = when (event.failure?.category) {
            FailureCategory.MEMORY -> getString(R.string.resource_pressure)
            FailureCategory.THERMAL -> getString(R.string.thermal_pause)
            else -> null
        }
        screen.setFailureMessage(currentChannel?.id in ChannelVisibilityPolicy.alwaysVisibleIds, message)
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

    private fun retryCurrentChannel() {
        if (!started || currentChannel == null) return
        pauseAvailabilityScan()
        val preferred = currentChannel?.let { selected ->
            health.onlineSources(selected).minByOrNull { tvPlayer.sourcePenalty(selected.id, it) }
        }
        tvPlayer.retry(preferredSource = preferred)
    }

    private fun pauseAvailabilityScan() {
        scanGeneration++
        scanner.cancel()
        health.cancelScan()
        scanning = false
        handler.removeCallbacks(periodicProbe)
        if (started) handler.postDelayed(periodicProbe, 15_000)
    }

    private fun onNetworkChanged(value: NetworkState) {
        if (!started) return
        networkState = value
        log.info("Network", "usable=${value.usable} validated=${value.validated} epoch=${value.epoch}")
        if (!value.usable) { tvPlayer.onNetwork(false); pauseAvailabilityScan() }
        else {
            val token = value.epoch
            handler.postDelayed({
                if (started && networkState.usable && token == networkState.epoch) {
                    tvPlayer.onNetwork(true)
                    checkAvailability()
                }
            }, 2_000)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::resourceMonitor.isInitialized) resourceMonitor.onTrimMemory(level)
    }

    private fun exportLogs() {
        summarizeRemote()
        log.info("Main", "Exporting session logs to clipboard")
        try {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BOBTV logs", log.export()))
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            log.error("Main", "Clipboard export failed", error)
            Toast.makeText(this, R.string.logs_copy_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLogsToDownloads() {
        if (savingLogs || destroyed) return
        savingLogs = true
        summarizeRemote()
        val filename = "BOBTV-log-" + LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")) + ".txt"
        log.info("Main", "Saving session log to Downloads/$filename")
        val snapshot = log.export()
        io.execute {
            val result = runCatching {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Unable to create Downloads entry")
                try {
                    val output = resolver.openOutputStream(uri)
                        ?: throw IOException("Unable to open log output stream")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                    val published = resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                    if (published != 1) throw IOException("Unable to publish Downloads entry")
                } catch (error: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                        .onFailure { error.addSuppressed(it) }
                    throw error
                }
            }
            result.onSuccess { log.info("Main", "Session log saved to Downloads/$filename") }
                .onFailure { log.error("Main", "Saving session log to Downloads failed", it) }
            savingLogs = false
            handler.post {
                if (destroyed || !started) return@post
                val message = if (result.isSuccess) getString(R.string.logs_saved, filename)
                    else getString(R.string.logs_save_failed)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val received = android.os.SystemClock.uptimeMillis()
        val result = if (::screen.isInitialized && screen.handleKeyEvent(event)) true else super.dispatchKeyEvent(event)
        if (::log.isInitialized && event.action == KeyEvent.ACTION_DOWN) {
            val finished = android.os.SystemClock.uptimeMillis()
            val delay = (finished - event.eventTime).coerceAtLeast(0)
            if (remoteDelays.size >= 128) remoteDelays.removeFirst()
            remoteDelays.addLast(delay)
            remoteMax = maxOf(remoteMax, delay)
            remoteEvents++
            if (finished - lastRemoteSummary >= 60_000) {
                log.info("Remote", "lastHandlerMs=" + (finished - received))
                summarizeRemote()
                lastRemoteSummary = finished
            }
        }
        return result
    }
    private fun adjustVolume(action: VolumeController.Action) {
        if (!started) return
        screen.showVolume(volume.adjust(action))
    }
    private fun summarizeRemote() {
        if (remoteDelays.isEmpty()) return
        val sorted = remoteDelays.sorted()
        log.info("Remote", "events=$remoteEvents dispatchP95Ms=" + sorted[((sorted.size - 1) * .95).toInt()] +
            " maxMs=$remoteMax recentSamples=" + sorted.size + " (app dispatch, not physical button-to-screen latency)")
    }

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

    private fun stopPlayback() {
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
        volume.stop()
        network.stop()
        resourceMonitor.stop()
        screen.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log.info("Main", "Background: playback and checks stopped")
    }

    override fun onResume() { super.onResume(); updates.resume() }
    override fun onPause() { updates.pause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { updates.saveState(outState); super.onSaveInstanceState(outState) }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!updates.activityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStop() {
        updates.stop()
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        updates.close()
        handler.removeCallbacksAndMessages(null)
        scanner.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
