package com.bobtv.helloworld.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class SampleMemoryException : IOException("Sample/heap memory safety limit reached")

object PlaybackErrors {
    fun classify(error: PlaybackException): PlaybackFailure {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val category = when {
            causes.any { it is SampleMemoryException } -> FailureCategory.MEMORY
            causes.any { it is CancellationException } -> FailureCategory.CANCELLED
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> FailureCategory.LIVE_WINDOW
            error.errorCode in 4000..4999 || error.errorCode in 5000..5999 -> FailureCategory.CODEC
            http == 401 || http == 403 -> FailureCategory.ACCESS
            http == 404 || http == 410 -> FailureCategory.MISSING_RESOURCE
            http == 408 || http == 429 || (http != null && http >= 500) -> FailureCategory.HTTP_TRANSIENT
            causes.any { it is UnknownHostException } -> FailureCategory.NETWORK
            causes.any { it is SocketTimeoutException } -> FailureCategory.TIMEOUT
            error.errorCode in 3000..3999 -> FailureCategory.PROTOCOL
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> FailureCategory.NETWORK
            else -> FailureCategory.UNKNOWN
        }
        return PlaybackFailure(category, "${error.errorCodeName} (${error.errorCode}): ${error.cause?.message.orEmpty()}",
            category != FailureCategory.CANCELLED, error.errorCode, http)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class BoundedLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(3) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.take(12).any { it is SampleMemoryException } ||
            loadErrorInfo.errorCount > 3) return C.TIME_UNSET
        val http = loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException
        if (http?.responseCode == 401 || http?.responseCode == 403) return C.TIME_UNSET
        if (http?.responseCode == 429 || http?.responseCode == 503) {
            val value = http.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
            return RetryAfterDelay.parse(value, System.currentTimeMillis())
        }
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        return listOf(1_000L, 2_000L, 5_000L)[(loadErrorInfo.errorCount - 1).coerceIn(0, 2)]
    }
}
