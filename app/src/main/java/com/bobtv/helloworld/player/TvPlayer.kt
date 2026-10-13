package com.bobtv.helloworld.player

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.bobtv.helloworld.diagnostics.SessionLog

@androidx.annotation.OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val view: PlayerView,
    private val log: SessionLog,
    private val onEvent: (Event) -> Unit,
) {
    enum class Status { LOADING, RETRYING, PLAYING, FAILED }
    data class Event(val channelId: String, val source: String, val status: Status,
                     val reason: String? = null, val sourceFailed: Boolean = false)
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    private var channel: Channel? = null
    private var fallback: SourceFallback? = null
    private var active = false
    private var transitioning = false
    private var generation = 0L
    private var attemptToken = 0L
    private var currentSource = ""
    private var startedAt = 0L
    private var timeout: Runnable? = null
    private var stable: Runnable? = null

    fun play(channel: Channel, preferredSource: String?) {
        generation++
        attemptToken++
        handler.removeCallbacksAndMessages(null)
        detachListener()
        active = true
        transitioning = false
        this.channel = channel
        fallback = SourceFallback(channel.sources, preferredSource)
        ensurePlayer()
        prepareSource(fallback!!.current())
    }

    private fun ensurePlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory().setUserAgent("BOBTV/2.1")
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setAllowCrossProtocolRedirects(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 20_000, 1_500, 3_000)
            .setTargetBufferBytes(16 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
            }
        view.player = player
    }

    private fun prepareSource(attempt: SourceFallback.Attempt) {
        val selected = channel ?: return
        val currentPlayer = player ?: return
        if (!active) return
        detachListener()
        attemptToken++
        val token = attemptToken
        val mediaId = selected.id + "|" + token
        currentSource = attempt.url
        transitioning = false
        startedAt = android.os.SystemClock.elapsedRealtime()
        listener = object : Player.Listener {
            private fun valid() = active && !transitioning && token == attemptToken &&
                currentPlayer.currentMediaItem?.mediaId == mediaId

            override fun onPlaybackStateChanged(state: Int) {
                if (!valid()) return
                when (state) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> {
                        stable?.let(handler::removeCallbacks)
                        scheduleTimeout(token)
                        emit(Status.LOADING)
                    }
                    Player.STATE_READY -> {
                        timeout?.let(handler::removeCallbacks)
                        stable?.let(handler::removeCallbacks)
                        stable = Runnable {
                            if (valid() && currentPlayer.isPlaying) fallback?.markStable()
                        }.also { handler.postDelayed(it, 30_000) }
                        emit(Status.PLAYING)
                        log.info(TAG, sourceContext() + " ready · " + elapsed() + " ms")
                    }
                    Player.STATE_ENDED -> fail("Live stream ended")
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (valid()) fail("Media3 " + error.errorCodeName + " (" + error.errorCode + ")", error)
            }
            override fun onRenderedFirstFrame() {
                if (valid()) log.info(TAG, sourceContext() + " first video frame · " + elapsed() + " ms")
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (valid()) log.info(TAG, sourceContext() + " tracks: audio=" +
                    tracks.isTypeSelected(C.TRACK_TYPE_AUDIO) + ", video=" + tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (valid()) log.info(TAG, sourceContext() + " audio session=" + audioSessionId)
            }
        }.also(currentPlayer::addListener)
        log.info(TAG, sourceContext() + " attempt round=" + attempt.round + " source=" + attempt.url)
        emit(Status.LOADING)
        currentPlayer.setMediaItem(MediaItem.Builder().setMediaId(mediaId).setUri(attempt.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8).build())
        currentPlayer.prepare()
        currentPlayer.play()
        scheduleTimeout(token)
    }

    fun updateSources(updated: Channel) {
        if (updated.id != channel?.id) return
        fallback?.updateSources(updated.sources)
        channel = updated
    }

    private fun scheduleTimeout(token: Long) {
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable {
            if (active && !transitioning && token == attemptToken) fail("Playback buffering timeout: 30000 ms")
        }.also { handler.postDelayed(it, 30_000) }
    }

    private fun fail(reason: String, error: Throwable? = null) {
        if (!active || transitioning) return
        transitioning = true
        handler.removeCallbacksAndMessages(null)
        log.warn(TAG, sourceContext() + " source failed · " + elapsed() + " ms · " + reason, error)
        emit(Status.RETRYING, reason, sourceFailed = true)
        detachListener()
        attemptToken++
        player?.stop()
        val next = fallback?.afterFailure()
        if (next == null) {
            log.error(TAG, sourceContext() + " all " + channel?.sources?.size +
                " sources exhausted after 3 passes · " + reason, error)
            emit(Status.FAILED, reason)
            return
        }
        log.info(TAG, sourceContext() + " switching to " + next.url + " · round=" + next.round +
            " · delay=" + next.delayMs + " ms")
        val token = generation
        // Defer replacement until onPlayerError's pending STATE_IDLE has been delivered.
        handler.postDelayed({
            if (active && token == generation) prepareSource(next)
        }, next.delayMs)
    }

    private fun sourceContext(): String = "channel=" + channel?.id + " source=" + currentSource
    private fun elapsed(): Long = android.os.SystemClock.elapsedRealtime() - startedAt
    private fun emit(status: Status, reason: String? = null, sourceFailed: Boolean = false) {
        channel?.let { onEvent(Event(it.id, currentSource, status, reason, sourceFailed)) }
    }

    private fun detachListener() {
        listener?.let { player?.removeListener(it) }
        listener = null
    }

    fun release() {
        active = false
        generation++
        attemptToken++
        handler.removeCallbacksAndMessages(null)
        detachListener()
        view.player = null
        player?.release()
        player = null
        log.info(TAG, "Player released")
    }

    companion object { private const val TAG = "BOBTV.Player" }
}
