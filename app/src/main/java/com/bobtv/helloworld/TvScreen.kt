package com.bobtv.helloworld

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.ScrollView
import com.bobtv.helloworld.updates.UpdateStatus
import com.bobtv.helloworld.updates.UpdatePhase
import com.bobtv.helloworld.updates.readBounded
import com.bobtv.helloworld.controls.ChannelTuning
import com.bobtv.helloworld.controls.RemoteDigits
import com.bobtv.helloworld.controls.RemoteVolume
import com.bobtv.helloworld.audio.VolumeController
import com.bobtv.helloworld.data.Channel
import com.bobtv.helloworld.data.ChannelNumbers
import com.bobtv.helloworld.player.TvPlayer

class TvScreen(
    private val activity: Activity,
    private val onSelect: (Channel) -> Unit,
    private val onRefresh: () -> Unit,
    private val onAcknowledge: () -> Unit,
    private val onExportLogs: () -> Unit,
    private val onExit: () -> Unit,
    private val onRetry: () -> Unit = {},
    private val onSaveLogs: () -> Unit = {},
    private val onVolume: (VolumeController.Action) -> Unit = {},
    private val onCheckUpdates: () -> Unit = {},
) {
    private enum class Mode { PLAYBACK, CHANNELS, SETTINGS, NOTES, EXIT }
    private var mode = Mode.PLAYBACK
    private var settingsReturn = Mode.PLAYBACK
    private var status = TvPlayer.Status.LOADING
    private var channels = emptyList<Channel>()
    private var navigable = emptyList<Channel>()
    private var current: Channel? = null
    private var menuCursor = 0
    private var overlayVisible = true
    private var checking = true
    private val handledKeys = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val root = view<FrameLayout>(R.id.root)
    private val safeContent = view<FrameLayout>(R.id.safe_content)
    private val menu = view<View>(R.id.menu_panel)
    private val error = view<View>(R.id.error_panel)
    private val exit = view<View>(R.id.exit_panel)
    private val settings = view<View>(R.id.settings_panel)
    private val notes = view<View>(R.id.notes_panel)
    private val notesScroll = view<ScrollView>(R.id.notes_scroll)
    private val loading = view<TextView>(R.id.loading)
    private val overlay = view<View>(R.id.channel_overlay)
    private val title = view<TextView>(R.id.channel_title)
    private val numberInput = view<TextView>(R.id.channel_input)
    private val volumeNotice = view<View>(R.id.volume_notice)
    private val volumeLabel = view<TextView>(R.id.volume_label)
    private val volumeBar = view<ProgressBar>(R.id.volume_bar)
    private val hideVolume = Runnable { volumeNotice.visibility = View.GONE }
    private val scrim = view<View>(R.id.scrim)
    private val list = view<ListView>(R.id.channel_list)
    private val refresh = view<Button>(R.id.refresh_channels)
    private val openSettings = view<Button>(R.id.open_settings)
    private val exportLogs = view<Button>(R.id.export_logs)
    private val saveLogs = view<Button>(R.id.save_logs)
    private val versionNotes = view<Button>(R.id.version_notes)
    private val checkUpdates = view<Button>(R.id.check_updates)
    private val settingsButtons get() = listOf(saveLogs, exportLogs, versionNotes, checkUpdates)
    @Suppress("DEPRECATION")
    private val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    private val empty = view<TextView>(R.id.empty_channels)
    private val catalogueStatus = view<TextView>(R.id.catalogue_status)
    private val accept = view<Button>(R.id.accept_change)
    private val retry = view<Button>(R.id.retry_channel)
    private val adapter = ChannelAdapter(activity)
    private val hideOverlay = Runnable { overlayVisible = false; render() }
    private val tuning = ChannelTuning(
        postDelayed = { action, delay -> handler.postDelayed(action, delay) },
        removeCallbacks = handler::removeCallbacks,
        onChanged = ::render,
        onSelect = onSelect,
    )

    init {
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, position, _ -> menuCursor = position; channels.getOrNull(position)?.let(onSelect) }
        refresh.setOnClickListener { menuCursor = channels.size; onRefresh() }
        openSettings.setOnClickListener { menuCursor = channels.size + 1; showSettings() }
        exportLogs.setOnClickListener { onExportLogs() }
        saveLogs.setOnClickListener { onSaveLogs() }
        versionNotes.text = activity.getString(R.string.version_notes, version)
        versionNotes.setOnClickListener { showNotes() }
        checkUpdates.setOnClickListener { onCheckUpdates() }
        view<Button>(R.id.close_notes).setOnClickListener { closeNotes() }
        accept.setOnClickListener { onAcknowledge() }
        retry.setOnClickListener { onRetry() }
        view<Button>(R.id.keep_watching).setOnClickListener { resumeView() }
        view<Button>(R.id.exit_app).setOnClickListener { onExit() }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0 &&
                (width != oldRight - oldLeft || height != oldBottom - oldTop)) {
                val horizontal = (width * 0.05f).toInt()
                val vertical = (height * 0.05f).toInt()
                safeContent.setPadding(horizontal, vertical, horizontal, vertical)
                val available = width - 2 * horizontal
                menu.layoutParams = (menu.layoutParams as FrameLayout.LayoutParams).apply {
                    this.width = (available * 0.60f).toInt()
                }
                for (panel in listOf(error, exit, settings, notes)) {
                    panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                        this.width = minOf(available, dp(640))
                        if (panel == settings || panel == notes) this.height = minOf(height - 2 * vertical, dp(600))
                    }
                }
            }
        }
        render()
    }

    fun setCatalogue(catalogue: List<Channel>, available: List<Channel> = catalogue,
                     navigable: List<Channel> = available, labels: Map<String, String> = emptyMap()) {
        this.navigable = navigable
        val previousNumber = tuning.numbers.numberOf(current?.id)
        val focusedId = channels.getOrNull(menuCursor)?.id
        val footerOffset = (menuCursor - channels.size).takeIf { it >= 0 }
        tuning.setCatalogue(catalogue, available)
        channels = tuning.available
        adapter.setCatalogue(channels, tuning.numbers, labels)
        menuCursor = if (footerOffset != null) channels.size + footerOffset.coerceAtMost(1) else
            channels.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
                ?: channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        updateTitle()
        if (current != null && mode == Mode.PLAYBACK &&
            previousNumber != tuning.numbers.numberOf(current?.id)) showOverlay()
        if (mode == Mode.CHANNELS) focusMenuCursor()
        render()
    }

    fun setCurrentChannel(channel: Channel) {
        tuning.cancel()
        current = channel
        status = TvPlayer.Status.LOADING
        updateTitle()
        setMode(Mode.PLAYBACK)
    }

    fun setPlaybackStatus(updated: TvPlayer.Status) {
        val newlyFailed = status != TvPlayer.Status.FAILED && updated == TvPlayer.Status.FAILED
        status = updated
        loading.setText(when (updated) {
            TvPlayer.Status.RETRYING -> R.string.retrying_channel
            TvPlayer.Status.WAITING_NETWORK -> R.string.waiting_network
            else -> R.string.loading_channel
        })
        if (newlyFailed && (mode == Mode.SETTINGS || mode == Mode.NOTES)) settingsReturn = Mode.PLAYBACK
        if (newlyFailed && mode != Mode.SETTINGS && mode != Mode.NOTES && mode != Mode.EXIT) setMode(Mode.PLAYBACK)
        else render()
        if (newlyFailed && mode == Mode.PLAYBACK) accept.requestFocus()
    }

    fun setFailureMessage(unitel: Boolean, reason: String? = null) {
        view<TextView>(R.id.error_help).text = reason ?: activity.getString(
            if (unitel) R.string.unitel_unavailable else R.string.channel_unavailable_help)
        accept.setText(if (unitel) R.string.choose_channel else R.string.accept_change)
        accept.setOnClickListener { if (unitel) showChannels() else onAcknowledge() }
    }

    fun setAvailability(checking: Boolean) {
        this.checking = checking
        empty.setText(if (checking) R.string.checking_channels else R.string.no_online_channels)
        if (current == null) loading.setText(R.string.checking_channels)
        render()
    }

    fun showEmptyChannels() {
        tuning.cancel()
        current = null
        status = TvPlayer.Status.FAILED
        updateTitle()
        showChannels()
    }

    fun setCatalogueStatus(text: String, refreshing: Boolean = false) {
        catalogueStatus.text = text
        refresh.setText(if (refreshing) R.string.refreshing_channels else R.string.refresh_channels)
    }

    private fun updateTitle() {
        val number = tuning.numbers.numberOf(current?.id)
        val displayed = number?.let(tuning.numbers::channelAt) ?: current
        current = displayed
        title.text = when {
            displayed == null -> ""
            number != null -> activity.getString(R.string.channel_label, ChannelNumbers.format(number), displayed.name)
            else -> activity.getString(R.string.channel_outside_catalogue, displayed.name)
        }
        view<TextView>(R.id.error_title).text = displayed?.let {
            activity.getString(R.string.channel_unavailable_named, it.name)
        } ?: activity.getString(R.string.channel_unavailable)
    }

    private fun showOverlay() {
        overlayVisible = true
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 5_000)
        render()
    }

    private fun showChannels() {
        handler.removeCallbacks(hideOverlay)
        menuCursor = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        setMode(Mode.CHANNELS)
    }

    private fun showSettings() {
        if (mode == Mode.SETTINGS) return
        if (mode == Mode.NOTES) { closeNotes(); return }
        settingsReturn = mode
        handler.removeCallbacks(hideOverlay)
        setMode(Mode.SETTINGS)
    }

    private fun showNotes() {
        view<TextView>(R.id.notes_title).text = activity.getString(R.string.patch_notes_title, version)
        view<TextView>(R.id.notes_text).text = runCatching {
            activity.assets.open("patch-notes.md").use { readBounded(it, 32 * 1024).toString(Charsets.UTF_8) }
        }.getOrElse { activity.getString(R.string.notes_unavailable) }
        notesScroll.scrollTo(0, 0)
        setMode(Mode.NOTES)
    }

    private fun closeNotes() { setMode(Mode.SETTINGS); versionNotes.requestFocus() }
    private fun moveSettings(delta: Int) {
        val buttons = settingsButtons.filter { it.isEnabled }
        val position = buttons.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
        buttons.getOrNull((position + delta).coerceIn(0, buttons.lastIndex))?.requestFocus()
    }

    fun setUpdateStatus(status: UpdateStatus) {
        view<TextView>(R.id.update_status).text = status.message.ifBlank { activity.getString(R.string.update_idle) }
        val progress = view<ProgressBar>(R.id.update_progress)
        val busy = status.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.PREPARING,
            UpdatePhase.INSTALLING, UpdatePhase.CONFIRMATION)
        progress.visibility = visible(busy)
        progress.isIndeterminate = status.percent < 0
        progress.progress = status.percent.coerceIn(0, 100)
        val wasFocused = checkUpdates.hasFocus()
        checkUpdates.isEnabled = !busy
        if (busy && wasFocused) versionNotes.requestFocus()
        if (mode == Mode.PLAYBACK && status.phase in listOf(UpdatePhase.PREPARING, UpdatePhase.INSTALLING, UpdatePhase.CONFIRMATION)) {
            loading.text = status.message
            loading.visibility = View.VISIBLE
        }
    }

    private fun setMode(updated: Mode) {
        if (updated != mode) tuning.cancel()
        mode = updated
        render()
        when (updated) {
            Mode.CHANNELS -> focusMenuCursor()
            Mode.EXIT -> view<Button>(R.id.keep_watching).requestFocus()
            Mode.SETTINGS -> saveLogs.requestFocus()
            Mode.NOTES -> view<Button>(R.id.close_notes).requestFocus()
            Mode.PLAYBACK -> {
                if (status == TvPlayer.Status.FAILED && current != null) accept.requestFocus() else root.requestFocus()
                showOverlay()
            }
        }
    }

    private fun focusMenuCursor() {
        adapter.highlightedPosition = if (menuCursor < channels.size) menuCursor else -1
        if (menuCursor == channels.size) refresh.requestFocus()
        else if (menuCursor > channels.size) openSettings.requestFocus()
        else {
            list.requestFocus()
            list.setSelection(menuCursor)
        }
    }

    private fun moveMenuCursor(delta: Int) {
        menuCursor = (menuCursor + delta).coerceIn(0, channels.size + 1)
        focusMenuCursor()
    }

    private fun changeChannel(delta: Int) {
        val next = tuning.numbers.adjacent(current?.id, navigable, delta) ?: return
        if (next.id == current?.id) showOverlay() else onSelect(next)
    }

    fun requestBack() {
        if (tuning.isPending) {
            tuning.cancel()
            return
        }
        when (mode) {
            Mode.CHANNELS -> if (current == null && status == TvPlayer.Status.FAILED) setMode(Mode.EXIT)
                else setMode(Mode.PLAYBACK)
            Mode.EXIT -> resumeView()
            Mode.SETTINGS -> setMode(settingsReturn)
            Mode.NOTES -> closeNotes()
            Mode.PLAYBACK -> if (status == TvPlayer.Status.FAILED && current != null) showChannels()
                else setMode(Mode.EXIT)
        }
    }

    private fun resumeView() {
        if (current == null && status == TvPlayer.Status.FAILED) showChannels() else setMode(Mode.PLAYBACK)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (RemoteVolume.handle(key, event.action, onVolume)) return true
        if (event.action == KeyEvent.ACTION_UP && handledKeys.remove(key)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (key in handledKeys) return true
        val canTune = mode == Mode.PLAYBACK || mode == Mode.CHANNELS
        val digit = RemoteDigits.fromKeyCode(key)
        if (canTune && digit != null) {
            tuning.append(digit, event.repeatCount)
            handledKeys.add(key)
            return true
        }
        if (canTune) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> tuning.cancel()
            }
        }
        val consumed = when (key) {
            KeyEvent.KEYCODE_BACK -> { requestBack(); true }
            KeyEvent.KEYCODE_MENU -> {
                if (mode == Mode.SETTINGS || mode == Mode.NOTES) requestBack() else showSettings()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (mode) {
                Mode.CHANNELS -> { moveMenuCursor(-1); true }
                Mode.PLAYBACK -> {
                    if (status == TvPlayer.Status.FAILED && current != null) retry.requestFocus()
                    else if (status != TvPlayer.Status.FAILED) changeChannel(1)
                    true
                }
                Mode.EXIT -> { view<Button>(R.id.keep_watching).requestFocus(); true }
                Mode.SETTINGS -> { moveSettings(-1); true }
                Mode.NOTES -> { notesScroll.scrollBy(0, -dp(80)); true }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (mode) {
                Mode.CHANNELS -> { moveMenuCursor(1); true }
                Mode.PLAYBACK -> {
                    if (status == TvPlayer.Status.FAILED && current != null) accept.requestFocus()
                    else if (status != TvPlayer.Status.FAILED) changeChannel(-1)
                    true
                }
                Mode.EXIT -> { view<Button>(R.id.exit_app).requestFocus(); true }
                Mode.SETTINGS -> { moveSettings(1); true }
                Mode.NOTES -> { notesScroll.scrollBy(0, dp(80)); true }
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (mode == Mode.PLAYBACK) {
                    if (status != TvPlayer.Status.FAILED) changeChannel(if (key == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when {
                    mode == Mode.EXIT -> {
                        if (view<Button>(R.id.exit_app).hasFocus()) onExit() else resumeView()
                        true
                    }
                    mode == Mode.SETTINGS -> {
                        settingsButtons.firstOrNull { it.hasFocus() && it.isEnabled }?.performClick()
                        true
                    }
                    mode == Mode.NOTES -> { closeNotes(); true }
                    canTune && tuning.isPending -> { tuning.confirm(); true }
                    mode == Mode.CHANNELS -> {
                        if (menuCursor == channels.size) onRefresh()
                        else if (menuCursor > channels.size) showSettings()
                        else channels.getOrNull(menuCursor)?.let(onSelect)
                        true
                    }
                    mode == Mode.PLAYBACK && status == TvPlayer.Status.FAILED && current != null -> {
                        if (retry.hasFocus()) onRetry() else accept.performClick()
                        true
                    }
                    mode == Mode.PLAYBACK -> { showChannels(); true }
                    else -> false
                }
            }
            else -> false
        }
        if (consumed) handledKeys.add(key)
        return consumed
    }

    fun showVolume(level: Int) {
        volumeLabel.text = activity.getString(if (level == 0) R.string.volume_muted else R.string.volume_level, level)
        volumeBar.progress = level
        volumeNotice.visibility = View.VISIBLE
        handler.removeCallbacks(hideVolume)
        handler.postDelayed(hideVolume, 3_000)
    }

    private fun render() {
        menu.visibility = visible(mode == Mode.CHANNELS)
        settings.visibility = visible(mode == Mode.SETTINGS)
        notes.visibility = visible(mode == Mode.NOTES)
        exit.visibility = visible(mode == Mode.EXIT)
        error.visibility = visible(mode == Mode.PLAYBACK && current != null && status == TvPlayer.Status.FAILED)
        loading.visibility = visible(mode == Mode.PLAYBACK &&
            (current != null || checking) && (status == TvPlayer.Status.LOADING || status == TvPlayer.Status.RETRYING || status == TvPlayer.Status.WAITING_NETWORK))
        overlay.visibility = visible(mode == Mode.PLAYBACK && current != null && overlayVisible)
        scrim.visibility = visible(mode != Mode.PLAYBACK || status == TvPlayer.Status.FAILED)
        val notice = tuning.notice
        numberInput.text = when {
            tuning.isPending -> activity.getString(R.string.channel_input, tuning.digits)
            notice != null -> activity.getString(
                if (notice.kind == ChannelTuning.NoticeKind.MISSING) R.string.channel_number_missing
                else R.string.channel_number_unavailable, notice.number)
            else -> ""
        }
        numberInput.visibility = visible((mode == Mode.PLAYBACK || mode == Mode.CHANNELS) &&
            (tuning.isPending || notice != null))
        if (mode != Mode.CHANNELS) adapter.highlightedPosition = -1
    }

    fun stop() {
        tuning.cancel()
        handler.removeCallbacksAndMessages(null)
        handledKeys.clear()
        volumeNotice.visibility = View.GONE
    }

    private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun <T : View> view(id: Int): T = activity.findViewById(id)
}
