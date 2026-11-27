package com.dewijones92.totum.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

internal class OpenTimes(private val clock: () -> Long = System::currentTimeMillis) {
    private val began = AtomicLong(0)
    private val opened = AtomicLong(0)

    val upstreamAsked: Boolean get() = began.get() != 0L

    fun upstreamBegan() {
        began.compareAndSet(0, clock())
    }

    fun upstreamOpened() {
        opened.compareAndSet(0, clock())
    }

    fun upstreamMs(now: Long = clock()): Long {
        val start = began.get()
        if (start == 0L) return 0
        return (opened.get().takeIf { it != 0L } ?: now) - start
    }
}

@UnstableApi
internal object RelayOpenTimes {

    fun timed(plain: DataSource.Factory): DataSource.Factory = DataSource.Factory { Timed(plain.createDataSource()) }

    private class Timed(private val inner: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) =
            inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val times = dataSpec.customData as? OpenTimes
            times?.upstreamBegan()
            try {
                return inner.open(dataSpec)
            } finally {
                times?.upstreamOpened()
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

        override fun getUri(): Uri? = inner.uri

        override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

        override fun close() = inner.close()
    }
}
