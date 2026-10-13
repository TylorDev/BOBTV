package com.bobtv.helloworld

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.media3.common.util.UnstableApi
import com.bobtv.helloworld.data.Channel
import com.bobtv.helloworld.data.ChannelSelection
import com.bobtv.helloworld.data.PlaylistRepository
import com.bobtv.helloworld.player.TvPlayer
import java.io.File
import java.util.concurrent.Executors

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var repository: PlaylistRepository
    private lateinit var screen: TvScreen
    private lateinit var tvPlayer: TvPlayer
    private var channels = emptyList<Channel>()
    private var currentChannel: Channel? = null
    private var started = false
    private var destroyed = false
    private var initialLoading = false
    private var refreshing = false
    private val preferences by lazy { getSharedPreferences("bobtv", MODE_PRIVATE) }
    private val periodicRefresh = object : Runnable {
        override fun run() {
            if (started) {
                refreshCatalogue()
                handler.postDelayed(this, PlaylistRepository.REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = PlaylistRepository(File(filesDir, "canales.m3u"),
            { assets.open("canales.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() } })
        screen = TvScreen(this, ::selectChannel, ::refreshCatalogue, ::retryCurrentChannel, ::finish)
        tvPlayer = TvPlayer(this, findViewById(R.id.player_view)) { status ->
            if (started) {
                screen.setPlaybackStatus(status)
                val keepAwake = status != TvPlayer.Status.FAILED
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (channels.isEmpty()) loadInitialCatalogue() else {
            selectChannel(ChannelSelection.restore(channels, currentChannel?.id ?: preferences.getString("last_channel", null)))
            refreshCatalogue()
        }
        handler.removeCallbacks(periodicRefresh)
        handler.postDelayed(periodicRefresh, PlaylistRepository.REFRESH_INTERVAL_MS)
    }

    private fun loadInitialCatalogue() {
        if (initialLoading || destroyed) return
        initialLoading = true
        io.execute {
            val initial = runCatching { repository.loadInitial() }
            handler.post {
                if (destroyed) return@post
                initialLoading = false
                initial.onSuccess { catalogue ->
                    channels = catalogue.channels
                    screen.setCatalogue(channels)
                    screen.setCatalogueStatus(resources.getQuantityString(
                        if (catalogue.fromCache) R.plurals.catalogue_cached else R.plurals.catalogue_bundled,
                        channels.size, channels.size))
                    currentChannel = ChannelSelection.restore(channels, preferences.getString("last_channel", null))
                    if (started) {
                        selectChannel(currentChannel!!)
                        refreshCatalogue()
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Could not read bundled or cached catalogue", error)
                    screen.setPlaybackStatus(TvPlayer.Status.FAILED)
                }
            }
        }
    }

    private fun refreshCatalogue() {
        if (refreshing || initialLoading || destroyed) return
        refreshing = true
        screen.setCatalogueStatus(getString(R.string.refreshing_channels), refreshing = true)
        io.execute {
            val result = repository.refresh()
            handler.post {
                if (destroyed) return@post
                refreshing = false
                when (result) {
                    is PlaylistRepository.RefreshResult.Updated -> {
                        channels = result.channels
                        screen.setCatalogue(channels)
                        screen.setCatalogueStatus(resources.getQuantityString(R.plurals.catalogue_updated, channels.size, channels.size))
                        Log.i(TAG, "Catalogue updated: ${channels.size} channels")
                        if (currentChannel == null && started) selectChannel(channels.first())
                    }
                    is PlaylistRepository.RefreshResult.Kept -> {
                        screen.setCatalogueStatus(getString(R.string.catalogue_kept))
                        Log.w(TAG, "Kept previous catalogue", result.error)
                    }
                }
            }
        }
    }

    private fun selectChannel(channel: Channel) {
        currentChannel = channel
        preferences.edit().putString("last_channel", channel.id).apply()
        screen.setCurrentChannel(channel)
        if (started) tvPlayer.play(channel)
    }

    private fun retryCurrentChannel() {
        val selected = currentChannel
        if (selected == null) loadInitialCatalogue()
        else selectChannel(channels.firstOrNull { it.id == selected.id } ?: selected)
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
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(periodicRefresh)
        tvPlayer.release()
        screen.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val TAG = "BOBTV" }
}
