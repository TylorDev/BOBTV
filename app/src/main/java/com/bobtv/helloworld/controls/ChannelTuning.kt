package com.bobtv.helloworld.controls

import com.bobtv.helloworld.data.Channel
import com.bobtv.helloworld.data.ChannelNumbers

/** Shared state for the list, numeric tuning and the current-channel banner. */
class ChannelTuning(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onChanged: () -> Unit,
    private val onSelect: (Channel) -> Unit,
) {
    enum class NoticeKind { MISSING, UNAVAILABLE }
    data class Notice(val number: String, val kind: NoticeKind)

    var numbers = ChannelNumbers(emptyList())
        private set
    var available = emptyList<Channel>()
        private set
    var notice: Notice? = null
        private set
    private var hideNotice: Runnable? = null
    private var noticeGeneration = 0L
    private val input = NumericChannelInput(postDelayed, removeCallbacks,
        onChanged = { onChanged() }, onConfirmed = ::selectNumber)
    val digits: String get() = input.digits
    val isPending: Boolean get() = input.isPending

    fun setCatalogue(catalogue: List<Channel>, available: List<Channel> = catalogue) {
        val updated = ChannelNumbers(catalogue)
        if (updated.ids != numbers.ids) cancel()
        numbers = updated
        this.available = updated.availableInOrder(available)
    }

    fun append(digit: Int, repeatCount: Int = 0) {
        if (repeatCount != 0) return
        clearNotice()
        input.append(digit)
    }

    fun confirm(): Boolean = input.confirm()

    fun cancel() {
        val hadInput = isPending
        val hadNotice = notice != null
        input.cancel()
        clearNotice()
        if (hadNotice && !hadInput) onChanged()
    }

    fun adjacent(currentId: String?, direction: Int): Channel? =
        numbers.adjacent(currentId, available, direction)

    private fun selectNumber(digits: String) {
        val number = digits.toInt()
        when (val target = numbers.resolve(number, available)) {
            is ChannelNumbers.Target.Available -> onSelect(target.channel)
            ChannelNumbers.Target.Missing -> showNotice(number, NoticeKind.MISSING)
            ChannelNumbers.Target.Unavailable -> showNotice(number, NoticeKind.UNAVAILABLE)
        }
    }

    private fun showNotice(number: Int, kind: NoticeKind) {
        clearNotice()
        notice = Notice(number.toString().padStart(2, '0'), kind)
        onChanged()
        val token = noticeGeneration
        hideNotice = Runnable {
            if (token == noticeGeneration) {
                notice = null
                hideNotice = null
                onChanged()
            }
        }.also { postDelayed(it, NOTICE_MS) }
    }

    private fun clearNotice() {
        noticeGeneration++
        hideNotice?.let(removeCallbacks)
        hideNotice = null
        notice = null
    }

    companion object { const val NOTICE_MS = 2_000L }
}
