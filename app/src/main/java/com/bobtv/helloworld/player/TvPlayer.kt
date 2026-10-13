package com.bobtv.helloworld.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.bobtv.helloworld.data.Channel

@androidx.annotation.OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val view: PlayerView,
    private val onStatus: (Status) -> Unit,
) {
    enum class Status { LOADING, RETRYING, PLAYING, FAILED }
    private val handler = Handler(Looper.getMainLooper())
    private val retries = RetrySchedule()
    private var player: ExoPlayer? = null
    private var channel: Channel? = null
    private var pendingRetry = false
    private var active = false
    private val retryTask = Runnable {
        pendingRetry = false
        if (active) prepareChannel()
    }
    private val loadingTimeout = Runnable {
        if (active && player?.playbackState == Player.STATE_BUFFERING) fail("Buffering timeout")
    }
    private val stablePlayback = Runnable { retries.reset() }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (!active || pendingRetry) return
            when (state) {
                Player.STATE_IDLE -> Unit
                Player.STATE_BUFFERING -> {
                    handler.removeCallbacks(stablePlayback)
                    handler.removeCallbacks(loadingTimeout)
                    handler.postDelayed(loadingTimeout, 30_000)
                    onStatus(Status.LOADING)
                }
                Player.STATE_READY -> {
                    handler.removeCallbacks(loadingTimeout)
                    handler.removeCallbacks(stablePlayback)
                    handler.postDelayed(stablePlayback, 30_000)
                    onStatus(Status.PLAYING)
                    Log.i(TAG, "Ready: ${channel?.id}")
                }
                Player.STATE_ENDED -> fail("Live stream ended")
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error ${error.errorCodeName}: ${channel?.id}", error)
            fail(error.errorCodeName)
        }
        override fun onRenderedFirstFrame() {
            Log.i(TAG, "First video frame: ${channel?.id}")
        }
        override fun onTracksChanged(tracks: Tracks) {
            Log.i(TAG, "Tracks: audio=${tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)}, video=${tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)}")
        }
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            Log.i(TAG, "Audio session: $audioSessionId")
        }
    }

    fun play(channel: Channel) {
        active = true
        cancelTasks()
        this.channel = channel
        retries.reset()
        if (player == null) {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent("BOBTV/2.0")
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 20_000, 1_500, 3_000)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            player = ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .setLoadControl(loadControl)
                .build().apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, true)
                    setHandleAudioBecomingNoisy(true)
                    addListener(listener)
                }
            view.player = player
        }
        prepareChannel()
    }

    private fun prepareChannel() {
        val selected = channel ?: return
        val currentPlayer = player ?: return
        handler.removeCallbacks(loadingTimeout)
        onStatus(Status.LOADING)
        currentPlayer.setMediaItem(MediaItem.Builder()
            .setMediaId(selected.id).setUri(selected.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8).build())
        currentPlayer.prepare()
        currentPlayer.play()
        Log.i(TAG, "Play: ${selected.id}")
    }

    private fun fail(reason: String) {
        if (!active || pendingRetry) return
        handler.removeCallbacks(loadingTimeout)
        handler.removeCallbacks(stablePlayback)
        // Suppress STATE_IDLE while stopping the failed item.
        pendingRetry = true
        player?.stop()
        val delay = retries.nextDelayMs()
        if (delay == null) {
            onStatus(Status.FAILED)
            Log.w(TAG, "Unavailable: ${channel?.id}; $reason")
        } else {
            onStatus(Status.RETRYING)
            handler.postDelayed(retryTask, delay)
            Log.i(TAG, "Retry in $delay ms: ${channel?.id}")
        }
    }

    fun release() {
        active = false
        cancelTasks()
        view.player = null
        player?.removeListener(listener)
        player?.release()
        player = null
        Log.i(TAG, "Player released")
    }

    private fun cancelTasks() {
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(loadingTimeout)
        handler.removeCallbacks(stablePlayback)
        pendingRetry = false
    }

    companion object { private const val TAG = "BOBTV.Player" }
}
