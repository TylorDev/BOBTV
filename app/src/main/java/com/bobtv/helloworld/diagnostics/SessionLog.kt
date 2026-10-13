package com.bobtv.helloworld.diagnostics

import java.net.URI
import java.time.Instant
import java.util.ArrayDeque

object LogRedactor {
    private val urls = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    fun redact(text: String): String = urls.replace(text) { match ->
        val value = match.value
        runCatching {
            val uri = URI(value)
            URI(uri.scheme, null, uri.host, uri.port, uri.path,
                if (uri.rawQuery == null) null else "REDACTED", null).toASCIIString()
        }.getOrElse {
            value.replace(Regex("""(https?://)[^/@\s]+@"""), "$1REDACTED@")
                .substringBefore('?').substringBefore('#') + "?REDACTED"
        }
    }
}

class SessionLog(
    header: String,
    private val maxEvents: Int = 2_000,
    private val maxBytes: Int = 256 * 1024,
    private val now: () -> Instant = Instant::now,
    private val sink: (level: String, tag: String, text: String) -> Unit = { _, _, _ -> },
) {
    private data class Entry(val text: String, val bytes: Int)
    private val header = LogRedactor.redact(header).take(2048) + "\n"
    private val entries = ArrayDeque<Entry>()
    private var bytes = 0
    private var dropped = false
    private val marker = "[Historial limitado: se omitieron eventos antiguos o demasiado largos]\n"
    private val budget = maxBytes - this.header.toByteArray(Charsets.UTF_8).size -
        marker.toByteArray(Charsets.UTF_8).size

    init { require(maxEvents > 0 && budget > 64) }

    fun info(tag: String, text: String) = record("I", tag, text)
    fun warn(tag: String, text: String, error: Throwable? = null) = record("W", tag, text, error)
    fun error(tag: String, text: String, error: Throwable? = null) = record("E", tag, text, error)

    @Synchronized
    private fun record(level: String, tag: String, message: String, error: Throwable? = null) {
        val detail = LogRedactor.redact(message + (error?.let { "\n" + it.stackTraceToString() } ?: ""))
        var text = now().toString() + " [" + level + "] [" + tag + "] " + detail + "\n"
        if (text.toByteArray(Charsets.UTF_8).size > budget) {
            text = limitUtf8(text, budget - 32) + "\n[Evento truncado]\n"
            dropped = true
        }
        val size = text.toByteArray(Charsets.UTF_8).size
        while (entries.isNotEmpty() && (entries.size >= maxEvents || bytes + size > budget)) {
            bytes -= entries.removeFirst().bytes
            dropped = true
        }
        entries.addLast(Entry(text, size))
        bytes += size
        sink(level, tag, detail)
    }

    @Synchronized
    fun export(): String = header + (if (dropped) marker else "") +
        entries.joinToString("") { it.text }

    @Synchronized
    fun eventCount(): Int = entries.size

    private fun limitUtf8(text: String, limit: Int): String {
        var end = 0
        var length = 0
        while (end < text.length) {
            val point = text.codePointAt(end)
            val next = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (length + next > limit) break
            length += next
            end += Character.charCount(point)
        }
        return text.substring(0, end)
    }
}
