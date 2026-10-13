package com.bobtv.helloworld.network

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.bobtv.helloworld.player.SampleMemoryException

/** Stops an in-flight large segment before the next read if memory is already unsafe. */
@androidx.annotation.OptIn(UnstableApi::class)
class GuardedDataSource(private val delegate: DataSource, private val unsafe: () -> Boolean) : DataSource {
    private var reads = 0
    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long { reads = 0; return delegate.open(dataSpec) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (reads++ % 16 == 0 && unsafe()) throw SampleMemoryException()
        return delegate.read(buffer, offset, length)
    }
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun close() = delegate.close()
}
