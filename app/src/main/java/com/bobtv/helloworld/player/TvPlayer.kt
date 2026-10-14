package com.bobtv.helloworld.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.bobtv.helloworld.data.Channel
import com.bobtv.helloworld.data.SourceKey
import com.bobtv.helloworld.diagnostics.*
import com.bobtv.helloworld.network.GuardedDataSource
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
class TvPlayer(private val context: Context, private val view: PlayerView, private val log: SessionLog,
               private val onEvent: (Event) -> Unit) {
    enum class Status { LOADING, RETRYING, PLAYING, WAITING_NETWORK, FAILED }
    data class Event(val channelId: String, val source: String, val status: Status,
                     val failure: PlaybackFailure? = null, val evidence: Boolean = false,
                     val generation: Long = 0, val attempt: Long = 0, val unstable: Boolean = false) {
        val reason: String? get() = failure?.detail
    }
    private val handler = Handler(Looper.getMainLooper())
    private val recovery = RecoveryCoordinator(SystemClock::elapsedRealtime)
    private val histories = linkedMapOf<SourceKey, SourceStability>()
    private val localRecoveries = mutableSetOf<SourceKey>()
    private val excludedCodecs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var player: ExoPlayer? = null
    private var volumeGain = 0f
    private var trackSelector: DefaultTrackSelector? = null
    private var listener: Player.Listener? = null
    private var channel: Channel? = null
    private var fallback: SourceFallback? = null
    private var active = false
    private var transitioning = false
    private val epoch = PlaybackEpoch()
    private val generation: Long get() = epoch.generation
    private val attemptToken: Long get() = epoch.attempt
    @Volatile private var currentSource = ""
    private var startedAt = 0L
    private var lastProgress = 0L
    private var lastTick = 0L
    private var firstFrameAt = 0L
    private var bufferingAt: Long? = null
    private var played = false
    private var networkUsable = true
    private var networkLost = false
    private var resourceSuspended = false
    private var memoryRecoveryUsed = false
    private var status = Status.LOADING
    private var readySince = 0L
    private var lastSummary = 0L
    private var lastEvidence = 0L
    private var lastPosition = 0L
    private var initialAdjusted = false
    private var counters: DecoderCounters? = null
    private var previousRendered = 0
    private var previousDropped = 0
    private var rendered = 0L
    private var dropped = 0L
    private var stableRendered = 0L
    private var stableDropped = 0L
    private var cuts = 0L
    private var stallMs = 0L
    private var audioUnderruns = 0L
    private var bitrateEstimate: Long? = null
    private val segmentRates = mutableMapOf<Int, java.util.ArrayDeque<Long>>()
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var videoMime = ""
    private var audioMime = ""
    private var resources: ResourceSnapshot? = null
    private var policy = PlaybackPolicy()
    private var loadControl = AdaptiveLoadControl()
    private var liveControl = AdaptiveLiveController()
    private var currentLive = LiveDecision(null, null, "NORMAL_LATENCY")
    private var lastPolicyDescription = ""
    private var lastSequence: Long? = null
    private var lastSequenceAt = 0L
    private var sequenceWarning = false
    private var retryNotBefore = 0L
    private var pendingSourceCut: Pair<SourceKey, Long>? = null
    val allowsBackgroundWork: Boolean get() = active && !transitioning && status == Status.PLAYING &&
        now() - readySince >= 15_000 && (player?.totalBufferedDuration ?: 0) >= 5_000 &&
        (resources?.level ?: ResourceLevel.NORMAL) == ResourceLevel.NORMAL
    val isFailed: Boolean get() = status == Status.FAILED
    val isWaitingNetwork: Boolean get() = status == Status.WAITING_NETWORK
    val allowsRecoveryProbe: Boolean get() = isFailed && !resourceSuspended &&
        (resources?.level ?: ResourceLevel.NORMAL) == ResourceLevel.NORMAL
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            samplePlayback()
            if (active) handler.postDelayed(this, 1_000)
        }
    }
    private val cooldown = Runnable {
        if (active && networkUsable && !resourceSuspended && status == Status.FAILED && recovery.canAutoRecover())
            retry(manual = false)
    }
    fun setVolume(gain: Float) {
        require(gain.isFinite())
        volumeGain = gain.coerceIn(0f, 1f)
        player?.volume = volumeGain
        log.info("Volume.Player", "Stored/applied gain=$volumeGain playerCreated=${player != null}")
    }
    fun play(channel: Channel, preferredSource: String?, manual: Boolean = true) {
        epoch.replace()
        handler.removeCallbacksAndMessages(null)
        detachListener()
        this.channel = channel
        currentSource = preferredSource ?: channel.sources.first()
        fallback = SourceFallback(channel.sources.sortedBy { sourcePenalty(channel.id, it) }, preferredSource)
        localRecoveries.clear(); memoryRecoveryUsed = false
        pendingSourceCut = null
        active = true; transitioning = false; resourceSuspended = false
        if (!recovery.begin(manual)) { exhausted("Cycle cooldown"); handler.post(tick); return }
        if (!networkUsable) {
            player?.stop()
            networkLost = true
            emit(Status.WAITING_NETWORK)
        } else if (resources?.level == ResourceLevel.STOP) suspendForResources()
        else { ensurePlayer(); prepareSource(fallback!!.current(), manual = manual) }
        lastTick = now()
        handler.post(tick)
    }
    fun retry(manual: Boolean = true, reconnect: Boolean = false, preferredSource: String? = null) {
        val selected = channel ?: return
        if (!active || resourceSuspended) return
        if (!networkUsable) { emit(Status.WAITING_NETWORK); return }
        if (!recovery.begin(manual || reconnect)) return
        epoch.replace()
        handler.removeCallbacksAndMessages(null)
        detachListener()
        transitioning = false
        fallback = SourceFallback(selected.sources.sortedBy { sourcePenalty(selected.id, it) },
            preferredSource ?: currentSource.takeIf { it in selected.sources })
        localRecoveries.clear()
        ensurePlayer()
        prepareSource(fallback!!.current(), manual, reconnect)
        lastTick = now()
        handler.post(tick)
    }
    fun updateSources(updated: Channel) {
        if (updated.id != channel?.id) return
        fallback?.updateSources(updated.sources)
        channel = updated
        val keys = updated.sources.map { SourceKey(updated.id, it) }.toSet()
        histories.keys.removeAll { it.channelId == updated.id && it !in keys && it.url != currentSource }
    }
    fun onNetwork(usable: Boolean) {
        val returned = usable && !networkUsable
        networkUsable = usable
        if (!active) return
        if (!usable) {
            networkLost = true
            if (player?.isPlaying != true) emit(Status.WAITING_NETWORK)
        } else if (returned && networkLost) {
            networkLost = false
            if (player?.playbackState == Player.STATE_READY || player?.playbackState == Player.STATE_BUFFERING) {
                player?.play()
                lastProgress = now()
                emit(if (player?.isPlaying == true) Status.PLAYING else Status.LOADING)
            } else retry(manual = false, reconnect = true)
        }
    }
    fun onResources(value: ResourceSnapshot) {
        val previous = resources?.level
        resources = value
        if (!active) return
        if (value.level == ResourceLevel.STOP) { suspendForResources(); return }
        if (resourceSuspended && value.level == ResourceLevel.NORMAL) {
            resourceSuspended = false
            retry(manual = false, reconnect = true)
        } else if (!resourceSuspended) {
            if (previous != value.level) {
                trackSelector?.let { selector ->
                    val low = value.level >= ResourceLevel.CRITICAL
                    selector.setParameters(selector.buildUponParameters().setMaxVideoSize(if (low) 854 else 1280, if (low) 480 else 720))
                }
            }
            if (previous != value.level && value.level >= ResourceLevel.CRITICAL) loadControl.trim()
            applyPolicy()
        }
    }
    private fun suspendForResources() {
        if (resourceSuspended) return
        resourceSuspended = true
        detachListener()
        epoch.nextAttempt()
        disposePlayer()
        emit(Status.FAILED, PlaybackFailure(
            if ((resources?.thermalStatus ?: -1) >= 4) FailureCategory.THERMAL else FailureCategory.MEMORY,
            "Playback suspended for device resources"))
    }
    private fun ensurePlayer() {
        if (player != null) return
        loadControl = AdaptiveLoadControl()
        liveControl = AdaptiveLiveController()
        val http = DefaultHttpDataSource.Factory().setUserAgent("BOBTV/2.2")
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setAllowCrossProtocolRedirects(true)
        val guarded = DataSource.Factory {
            GuardedDataSource(http.createDataSource()) {
                val runtime = Runtime.getRuntime()
                loadControl.allocatedBytes > 40 * BufferProfile.MIB ||
                    runtime.totalMemory() - runtime.freeMemory() >= runtime.maxMemory() * 9 / 10
            }
        }
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                        enableAudioOutputPlaybackParams: Boolean): AudioSink =
                PcmAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(false).build())
        }.setEnableDecoderFallback(true)
            .experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val at = now()
                excludedCodecs.entries.removeIf { it.value <= at }
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                    .filter { codec -> !excludedCodecs.containsKey(codecKey(currentSource, mime, codec.name)) }
                    .sortedBy { if (it.hardwareAccelerated) 0 else if (it.softwareOnly) 2 else 1 }
            }
        val tracks = DefaultTrackSelector(context).apply {
            val low = (resources?.level ?: ResourceLevel.NORMAL) >= ResourceLevel.CRITICAL
            setParameters(buildUponParameters().setMaxVideoSize(if (low) 854 else 1280, if (low) 480 else 720).setMaxVideoFrameRate(30)
                .setTunnelingEnabled(false)
                .setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build())
                .setExceedVideoConstraintsIfNecessary(true))
        }
        trackSelector = tracks
        player = ExoPlayer.Builder(context, renderers).setTrackSelector(tracks)
            .setMediaSourceFactory(DefaultMediaSourceFactory(guarded).setLoadErrorHandlingPolicy(BoundedLoadErrorPolicy()))
            .setLoadControl(loadControl).setLivePlaybackSpeedControl(liveControl).build().apply {
                volume = volumeGain
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                addAnalyticsListener(analytics)
            }
        view.player = player
        log.info(TAG, "Single player created; SurfaceView; prefer 720p/30 with higher-quality fallback; " +
            "audio=PCM passthrough=false offload=false tunneling=false gain=$volumeGain before prepare/play")
    }
    private fun prepareSource(attempt: SourceFallback.Attempt, manual: Boolean = false, reconnect: Boolean = false) {
        if (!active || resourceSuspended) return
        if (!networkUsable) { emit(Status.WAITING_NETWORK); return }
        if (!recovery.permitPreparation(reconnect, manual)) { exhausted("Preparation quota/cycle exhausted"); return }
        val selected = channel ?: return
        val currentPlayer = player ?: return
        currentPlayer.volume = volumeGain
        detachListener()
        epoch.nextAttempt()
        val token = attemptToken
        val mediaId = selected.id + "|" + token
        currentSource = attempt.url
        transitioning = false
        played = false; bufferingAt = null; firstFrameAt = 0; initialAdjusted = false
        counters = null; previousRendered = 0; previousDropped = 0
        startedAt = now(); lastProgress = startedAt; lastPosition = 0; lastTick = startedAt
        retryNotBefore = 0L
        lastSequence = null; lastSequenceAt = startedAt; sequenceWarning = false
        videoDecoder = ""; audioDecoder = ""; videoMime = ""; audioMime = ""
        segmentRates.clear(); bitrateEstimate = null
        currentLive = LiveDecision(null, null, "NORMAL_LATENCY")
        liveControl.update(null, false)
        listener = object : Player.Listener {
            private fun valid() = active && !transitioning && token == attemptToken &&
                currentPlayer.currentMediaItem?.mediaId == mediaId
            override fun onPlaybackStateChanged(state: Int) {
                if (!valid()) return
                when (state) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> {
                        if (played && bufferingAt == null) bufferingAt = now()
                        emit(if (networkUsable) Status.LOADING else Status.WAITING_NETWORK)
                    }
                    Player.STATE_READY -> {
                        finishCut()
                        readySince = now()
                        emit(if (played) Status.PLAYING else Status.LOADING)
                    }
                    Player.STATE_ENDED -> fail(PlaybackFailure(FailureCategory.ENDED, "Live stream ended"))
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!valid()) return
                val failure = PlaybackErrors.classify(error)
                if (failure.category == FailureCategory.CANCELLED) return
                if (!networkUsable) { emit(Status.WAITING_NETWORK, failure); return }
                if (failure.category == FailureCategory.LIVE_WINDOW && localRecoveries.add(key())) recoverPosition(failure)
                else if (failure.category == FailureCategory.CODEC && localRecoveries.add(key())) {
                    val format = (error as? ExoPlaybackException)?.rendererFormat
                    val mime = format?.sampleMimeType ?: videoMime
                    val codec = if (mime.startsWith("audio/")) audioDecoder else videoDecoder
                    if (codec.isNotBlank()) excludedCodecs[codecKey(currentSource, mime, codec)] = now() + 300_000
                    log.warn(TAG, "Local codec failure: " + codec + " mime=" + mime + " format=" + format + "; not offline", error)
                    fail(failure, error, retrySame = true)
                } else if (failure.category == FailureCategory.MEMORY && !memoryRecoveryUsed) {
                    memoryRecoveryUsed = true
                    policy = PlaybackPolicy(BufferProfile.PRESSURE)
                    loadControl.update(policy)
                    detachListener(); disposePlayer(); ensurePlayer()
                    fail(failure, error, retrySame = true)
                } else fail(failure, error)
            }
            override fun onRenderedFirstFrame() {
                if (!valid()) return
                firstFrameAt = now()
                evidence()
                log.info(TAG, sourceContext() + " first video frame · " + (now() - startedAt) + " ms")
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (!valid()) return
                applyPolicy()
                if (!initialAdjusted && !played && currentLive.positionMs != null && currentPlayer.totalBufferedDuration == 0L) {
                    initialAdjusted = true
                    currentPlayer.seekTo(currentLive.positionMs!!)
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (valid()) log.info(TAG, sourceContext() + " tracks audio=" +
                    tracks.isTypeSelected(C.TRACK_TYPE_AUDIO) + " video=" + tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
            }
        }.also(currentPlayer::addListener)
        log.info(TAG, sourceContext() + " generation=" + generation + " token=" + token + " round=" + attempt.round)
        emit(Status.LOADING)
        currentPlayer.setMediaItem(MediaItem.Builder().setMediaId(mediaId).setUri(attempt.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8).build())
        applyPolicy()
        currentPlayer.prepare()
        currentPlayer.play()
    }
    private fun recoverPosition(failure: PlaybackFailure) {
        if (!recovery.permitPreparation()) { exhausted(failure.detail); return }
        val currentPlayer = player ?: return
        applyPolicy()
        log.warn(TAG, sourceContext() + " recovering expired window: " + currentLive)
        transitioning = true
        val ticket = epoch.ticket()
        handler.post {
            if (!active || !epoch.accepts(ticket)) return@post
            transitioning = false
            currentLive.positionMs?.let(currentPlayer::seekTo) ?: currentPlayer.seekToDefaultPosition()
            currentPlayer.prepare()
            lastProgress = now()
            emit(Status.RETRYING, failure)
        }
    }
    private fun finishCut() {
        bufferingAt?.let { at ->
            val duration = now() - at
            if (duration >= 500) {
                history().onCut(duration, now()); cuts++; stallMs += duration
                recovery.progress(0, duration)
                log.info(TAG, sourceContext() + " rebuffer=" + duration + " ms")
            }
        }
        bufferingAt = null
        applyPolicy()
    }
    private fun evidence() {
        pendingSourceCut?.let { (failedKey, at) ->
            if (failedKey.channelId == channel?.id) histories[failedKey]?.onCut(now() - at, now())
        }
        pendingSourceCut = null
        played = true; lastProgress = now(); lastEvidence = now()
        emit(Status.PLAYING, evidence = true)
    }
    private fun samplePlayback() {
        val currentPlayer = player
        val at = now()
        val elapsed = (at - lastTick).coerceIn(0, 2_000)
        lastTick = at
        val frames = counters?.let {
            it.ensureUpdated()
            val r = (it.renderedOutputBufferCount - previousRendered).coerceAtLeast(0)
            val d = (it.droppedBufferCount - previousDropped).coerceAtLeast(0)
            previousRendered = it.renderedOutputBufferCount; previousDropped = it.droppedBufferCount
            rendered += r; dropped += d
            if (currentPlayer?.isPlaying == true && firstFrameAt != 0L && at - firstFrameAt > 5_000) {
                stableRendered += r; stableDropped += d
            }
            r
        } ?: 0
        if (!transitioning && currentPlayer != null) {
            val position = currentPlayer.currentPosition
            val video = currentPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            val advancing = currentPlayer.isPlaying && position > lastPosition + 50 && (!video || frames > 0 || (counters == null && firstFrameAt != 0L))
            if (advancing) {
                lastProgress = at
                if (!played || at - lastEvidence >= 5_000) evidence()
                history().played(elapsed)
                if (recovery.progress(elapsed, 0)) fallback?.markStable()
            }
            lastPosition = position
            if (!networkUsable && currentPlayer.isPlaying) lastProgress = at
            if (!currentPlayer.playWhenReady || currentPlayer.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                lastProgress = at
            if (!resourceSuspended && at >= retryNotBefore && at - lastProgress >= 30_000 && status != Status.FAILED) {
                if (!networkUsable) { currentPlayer.stop(); emit(Status.WAITING_NETWORK) }
                else fail(PlaybackFailure(FailureCategory.TIMEOUT, "30 seconds without useful playback progress"))
            }
            applyPolicy()
        }
        if (at - lastSummary >= 60_000) {
            lastSummary = at
            val actual = currentPlayer?.currentLiveOffset?.takeIf { it != C.TIME_UNSET }
            val relative = currentPlayer?.let { if (it.duration != C.TIME_UNSET) it.duration - it.currentPosition else null }
            log.info(TAG, sourceContext() + " summary status=" + status + " cuts=" + cuts + " stallMs=" + stallMs +
                " rendered=" + rendered + " dropped=" + dropped + " stableRendered=" + stableRendered + " stableDropped=" + stableDropped +
                " audioUnderruns=" + audioUnderruns + " bufferMs=" + currentPlayer?.totalBufferedDuration +
                " allocated=" + loadControl.allocatedBytes + " unused=" + loadControl.unusedBytes +
                " profile=" + policy.profile + " sampleBudget=" + policy.targetBytes + " bitrateEstimate=" + bitrateEstimate +
                " liveTarget=" + currentLive.targetMs + " liveActual=" + actual + " relativeToPublishedEnd=" + relative + " reason=" + currentLive.reason)
        }
    }
    private fun applyPolicy() {
        if (!active) return
        val currentPlayer = player
        val stability = history()
        val level = resources?.level ?: ResourceLevel.NORMAL
        val selectedProfile = if (memoryRecoveryUsed || level >= ResourceLevel.CRITICAL) BufferProfile.PRESSURE else stability.profile
        var next = PlaybackPolicy(selectedProfile)
        val timeline = currentPlayer?.currentTimeline
        var requested = stability.requestedOffset(liveControl.normalOffsetMs)
        if (timeline != null && !timeline.isEmpty && currentPlayer.isCurrentMediaItemLive) {
            val window = timeline.getWindow(currentPlayer.currentMediaItemIndex, Timeline.Window())
            val manifest = currentPlayer.currentManifest as? HlsManifest
            val media = manifest?.mediaPlaylist
            val segmentMs = (media?.targetDurationUs?.div(1000) ?: 6_000).coerceAtLeast(1_000)
            val duration = window.durationMs
            val actual = currentPlayer.currentLiveOffset
            val lag = if (actual == C.TIME_UNSET) 0 else (actual + currentPlayer.currentPosition - duration).coerceAtLeast(0)
            val config = window.liveConfiguration
            requested = stability.requestedOffset(config?.targetOffsetMs?.takeIf { it != C.TIME_UNSET } ?: liveControl.normalOffsetMs)
            currentLive = LiveWindowPolicy.resolve(if (media?.hasEndTag == true || !window.isDynamic) null else requested,
                LiveWindow(duration, lag, segmentMs, window.isSeekable,
                    config?.minOffsetMs?.takeIf { it != C.TIME_UNSET }, config?.maxOffsetMs?.takeIf { it != C.TIME_UNSET }))
            if (currentLive.targetMs != null) next = next.copy(targetOffsetMs = currentLive.targetMs,
                maxBufferMs = minOf(next.maxBufferMs, (currentLive.targetMs!! - lag - maxOf(2_000, segmentMs)).coerceAtLeast(500)))
            media?.let {
                if (lastSequence != it.mediaSequence) {
                    lastSequence = it.mediaSequence; lastSequenceAt = now(); sequenceWarning = false
                } else if (!sequenceWarning && now() - lastSequenceAt >= maxOf(60_000, segmentMs * 3)) {
                    sequenceWarning = true
                    log.warn(TAG, sourceContext() + " HLS sequence stalled; inconclusive, not offline")
                }
            }
        } else currentLive = LiveDecision(null, null, if (requested == null) "NORMAL_LATENCY" else "LIVE_WINDOW_UNAVAILABLE")
        val runtime = Runtime.getRuntime()
        next = next.boundedByHeap(runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory(), policy.targetBytes)
        if (level == ResourceLevel.LIMITED) next = next.copy(targetBytes = minOf(next.targetBytes, 16 * BufferProfile.MIB))
        bitrateEstimate?.takeIf { it > 0 }?.let { rate ->
            val capacityMs = (next.targetBytes * 8_000L / (rate * 1.2).toLong().coerceAtLeast(1)).coerceAtLeast(500)
            next = next.copy(maxBufferMs = minOf(next.maxBufferMs, capacityMs))
        }
        policy = next
        loadControl.update(next)
        liveControl.update(currentLive.targetMs, level != ResourceLevel.NORMAL)
        val description = next.profile.toString() + " " + next.targetBytes + " " + currentLive.targetMs + " " + currentLive.reason
        if (description != lastPolicyDescription) {
            lastPolicyDescription = description
            log.info(TAG, sourceContext() + " policy=" + description)
        }
    }
    private fun fail(failure: PlaybackFailure, error: Throwable? = null, retrySame: Boolean = false) {
        if (!active || transitioning || resourceSuspended) return
        if (played && bufferingAt == null && failure.category in setOf(FailureCategory.NETWORK,
                FailureCategory.HTTP_TRANSIENT, FailureCategory.MISSING_RESOURCE, FailureCategory.TIMEOUT))
            pendingSourceCut = key() to now()
        finishCut()
        transitioning = true
        log.warn(TAG, sourceContext() + " failure category=" + failure.category + " · " + failure.detail, error)
        emit(Status.RETRYING, failure)
        detachListener(); epoch.nextAttempt()
        player?.stop()
        val next = if (retrySame) fallback?.current() else fallback?.afterFailure()
        if (next == null) { exhausted(failure.detail); return }
        val ticket = epoch.ticket()
        handler.postDelayed({ if (active && epoch.accepts(ticket)) prepareSource(next) },
            if (retrySame) 1_000 else next.delayMs)
    }
    private fun exhausted(reason: String) {
        transitioning = false
        player?.stop()
        recovery.exhausted()
        log.warn(TAG, sourceContext() + " cycle exhausted; remains selectable; " + reason)
        emit(Status.FAILED, PlaybackFailure(FailureCategory.UNKNOWN, reason))
        handler.removeCallbacks(cooldown)
        handler.postDelayed(cooldown, 60_000)
    }
    private val analytics = object : AnalyticsListener {
        private fun valid(eventTime: AnalyticsListener.EventTime): Boolean {
            val timeline = eventTime.timeline
            if (!active || transitioning || timeline.isEmpty || eventTime.windowIndex >= timeline.windowCount) return false
            return timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId == player?.currentMediaItem?.mediaId
        }
        private fun decoder(name: String, mime: String) {
            val infos = runCatching { MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false) }.getOrDefault(emptyList())
            val info = infos.firstOrNull { it.name == name }
            log.info(TAG, sourceContext() + " actualDecoder=" + name + " mime=" + mime + " hardwareDeclared=" +
                info?.hardwareAccelerated + " softwareDeclared=" + info?.softwareOnly + " (manufacturer declaration)")
        }
        override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            if (valid(eventTime)) { counters = decoderCounters; previousRendered = 0; previousDropped = 0 }
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (valid(eventTime)) { videoDecoder = decoderName; decoder(decoderName, videoMime) }
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (valid(eventTime)) { audioDecoder = decoderName; decoder(decoderName, audioMime) }
        }
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?) {
            if (valid(eventTime)) { videoMime = format.sampleMimeType.orEmpty(); log.info(TAG, sourceContext() + " videoFormat=" + format) }
        }
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?) {
            if (valid(eventTime)) { audioMime = format.sampleMimeType.orEmpty(); log.info(TAG, sourceContext() + " audioFormat=" + format) }
        }
        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            if (valid(eventTime)) {
                log.info(TAG, sourceContext() + " first advancing audio · " + (now() - startedAt) + " ms")
                if (player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) != true) evidence()
            }
        }
        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
            bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            if (valid(eventTime)) { audioUnderruns++; log.warn(TAG, sourceContext() + " audio underrun") }
        }
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData) {
            if (!valid(eventTime) || mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
            val duration = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
            if (mediaLoadData.mediaStartTimeMs != C.TIME_UNSET && duration > 0 && loadEventInfo.bytesLoaded > 0) {
                val type = if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_VIDEO
                val rates = segmentRates.getOrPut(type) { java.util.ArrayDeque() }
                if (rates.size >= 6) rates.removeFirst()
                rates.addLast(loadEventInfo.bytesLoaded * 8_000 / duration)
                if (segmentRates.values.all { it.size >= 3 }) bitrateEstimate = segmentRates.values.sumOf { it.average().toLong() }
            }
        }
        override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
            if (!valid(eventTime)) return
            val http = generateSequence<Throwable>(error) { it.cause }.take(12)
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            if (http != null && (http.responseCode == 429 || http.responseCode == 503)) {
                val value = http.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
                if (value != null) retryNotBefore = now() + RetryAfterDelay.parse(value, System.currentTimeMillis())
            }
            log.warn(TAG, sourceContext() + " loadError type=" + mediaLoadData.dataType +
                " uri=" + loadEventInfo.uri + " cancelled=" + wasCanceled, error)
        }
    }
    private fun history(): SourceStability {
        val key = key()
        return histories[key] ?: SourceStability().also {
            if (histories.size >= 64) histories.remove(histories.keys.first())
            histories[key] = it
        }
    }
    private fun codecKey(source: String, mime: String, name: String) = source + "|" + mime + "|" + name
    fun sourcePenalty(channelId: String, source: String): Int = histories[SourceKey(channelId, source)]?.profile?.ordinal ?: 0
    private fun key() = SourceKey(channel?.id.orEmpty(), currentSource)
    private fun now() = SystemClock.elapsedRealtime()
    private fun sourceContext() = "channel=" + channel?.id + " source=" + currentSource
    private fun emit(updated: Status, failure: PlaybackFailure? = null, evidence: Boolean = false) {
        status = updated
        channel?.let { onEvent(Event(it.id, currentSource, updated, failure, evidence, generation, attemptToken,
            histories[key()]?.profile?.let { profile -> profile != BufferProfile.NORMAL } == true)) }
    }
    private fun detachListener() { listener?.let { player?.removeListener(it) }; listener = null }
    private fun disposePlayer() {
        view.player = null
        player?.removeAnalyticsListener(analytics)
        player?.release()
        player = null; counters = null; trackSelector = null
        log.info(TAG, "Player released")
    }
    fun release() {
        active = false; epoch.replace()
        handler.removeCallbacksAndMessages(null)
        detachListener(); disposePlayer()
    }
    companion object { private const val TAG = "Player" }
}
