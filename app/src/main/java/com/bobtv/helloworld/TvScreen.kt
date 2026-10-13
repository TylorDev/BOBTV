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
import com.bobtv.helloworld.data.Channel
import com.bobtv.helloworld.player.TvPlayer

class TvScreen(
    private val activity: Activity,
    private val onSelect: (Channel) -> Unit,
    private val onRefresh: () -> Unit,
    private val onRetry: () -> Unit,
    private val onExit: () -> Unit,
) {
    private enum class Mode { PLAYBACK, CHANNELS, EXIT }
    private var mode = Mode.PLAYBACK
    private var status = TvPlayer.Status.LOADING
    private var channels = emptyList<Channel>()
    private var current: Channel? = null
    private var menuCursor = 0
    private var overlayVisible = true
    private val handledKeys = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val root = view<FrameLayout>(R.id.root)
    private val safeContent = view<FrameLayout>(R.id.safe_content)
    private val menu = view<View>(R.id.menu_panel)
    private val error = view<View>(R.id.error_panel)
    private val exit = view<View>(R.id.exit_panel)
    private val loading = view<TextView>(R.id.loading)
    private val overlay = view<View>(R.id.channel_overlay)
    private val title = view<TextView>(R.id.channel_title)
    private val scrim = view<View>(R.id.scrim)
    private val list = view<ListView>(R.id.channel_list)
    private val refresh = view<Button>(R.id.refresh_channels)
    private val catalogueStatus = view<TextView>(R.id.catalogue_status)
    private val retry = view<Button>(R.id.retry_channel)
    private val adapter = ChannelAdapter(activity)
    private val hideOverlay = Runnable { overlayVisible = false; render() }

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> channels.getOrNull(position)?.let(onSelect) }
        refresh.setOnClickListener { menuCursor = channels.size; onRefresh() }
        retry.setOnClickListener { onRetry() }
        view<Button>(R.id.choose_channel).setOnClickListener { showChannels() }
        view<Button>(R.id.keep_watching).setOnClickListener { setMode(Mode.PLAYBACK) }
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
                for (panel in listOf(error, exit)) {
                    panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                        this.width = minOf(available, dp(640))
                    }
                }
            }
        }
        render()
    }

    fun setCatalogue(updated: List<Channel>) {
        val focusedId = channels.getOrNull(menuCursor)?.id
        val wasRefresh = menuCursor == channels.size && mode == Mode.CHANNELS
        channels = updated
        adapter.channels = updated
        menuCursor = if (wasRefresh) channels.size else
            channels.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
                ?: channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        updateTitle()
        if (mode == Mode.CHANNELS) focusMenuCursor()
    }

    fun setCurrentChannel(channel: Channel) {
        current = channel
        status = TvPlayer.Status.LOADING
        updateTitle()
        setMode(Mode.PLAYBACK)
        showOverlay()
    }

    fun setPlaybackStatus(updated: TvPlayer.Status) {
        val newlyFailed = status != TvPlayer.Status.FAILED && updated == TvPlayer.Status.FAILED
        status = updated
        loading.setText(if (updated == TvPlayer.Status.RETRYING) R.string.retrying_channel else R.string.loading_channel)
        render()
        if (newlyFailed && mode == Mode.PLAYBACK) retry.requestFocus()
    }

    fun setCatalogueStatus(text: String, refreshing: Boolean = false) {
        catalogueStatus.text = text
        refresh.setText(if (refreshing) R.string.refreshing_channels else R.string.refresh_channels)
    }

    private fun updateTitle() {
        val displayed = channels.firstOrNull { it.id == current?.id } ?: current
        title.text = displayed?.name.orEmpty()
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

    private fun setMode(updated: Mode) {
        mode = updated
        render()
        when (updated) {
            Mode.CHANNELS -> focusMenuCursor()
            Mode.EXIT -> view<Button>(R.id.keep_watching).requestFocus()
            Mode.PLAYBACK -> {
                if (status == TvPlayer.Status.FAILED) retry.requestFocus() else root.requestFocus()
                showOverlay()
            }
        }
    }

    private fun focusMenuCursor() {
        adapter.highlightedPosition = if (menuCursor < channels.size) menuCursor else -1
        if (menuCursor >= channels.size) refresh.requestFocus()
        else {
            list.requestFocus()
            list.setSelection(menuCursor)
        }
    }

    private fun moveMenuCursor(delta: Int) {
        menuCursor = (menuCursor + delta).coerceIn(0, channels.size)
        focusMenuCursor()
    }

    private fun changeChannel(delta: Int) {
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.id == current?.id }
        val next = if (index < 0) 0 else (index + delta + channels.size) % channels.size
        onSelect(channels[next])
    }

    fun requestBack() {
        when (mode) {
            Mode.CHANNELS, Mode.EXIT -> setMode(Mode.PLAYBACK)
            Mode.PLAYBACK -> setMode(Mode.EXIT)
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && handledKeys.remove(key)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (key in handledKeys) return true
        val consumed = when (key) {
            KeyEvent.KEYCODE_BACK -> { requestBack(); true }
            KeyEvent.KEYCODE_DPAD_UP -> when (mode) {
                Mode.CHANNELS -> { moveMenuCursor(-1); true }
                Mode.PLAYBACK -> { changeChannel(1); true }
                Mode.EXIT -> false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (mode) {
                Mode.CHANNELS -> { moveMenuCursor(1); true }
                Mode.PLAYBACK -> { changeChannel(-1); true }
                Mode.EXIT -> false
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (mode == Mode.PLAYBACK) {
                    changeChannel(if (key == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when {
                    mode == Mode.CHANNELS -> {
                        if (menuCursor >= channels.size) onRefresh()
                        else channels.getOrNull(menuCursor)?.let(onSelect)
                        true
                    }
                    mode == Mode.PLAYBACK && status != TvPlayer.Status.FAILED -> { showChannels(); true }
                    else -> false
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> mode == Mode.CHANNELS
            else -> false
        }
        if (consumed) handledKeys.add(key)
        return consumed
    }

    private fun render() {
        menu.visibility = visible(mode == Mode.CHANNELS)
        exit.visibility = visible(mode == Mode.EXIT)
        error.visibility = visible(mode == Mode.PLAYBACK && status == TvPlayer.Status.FAILED)
        loading.visibility = visible(mode == Mode.PLAYBACK &&
            (status == TvPlayer.Status.LOADING || status == TvPlayer.Status.RETRYING))
        overlay.visibility = visible(mode == Mode.PLAYBACK && overlayVisible && status != TvPlayer.Status.FAILED)
        scrim.visibility = visible(mode != Mode.PLAYBACK || status == TvPlayer.Status.FAILED)
        if (mode != Mode.CHANNELS) adapter.highlightedPosition = -1
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        handledKeys.clear()
    }

    private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun <T : View> view(id: Int): T = activity.findViewById(id)
}
