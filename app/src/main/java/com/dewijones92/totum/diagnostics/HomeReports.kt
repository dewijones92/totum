package com.dewijones92.totum.diagnostics

interface HomeReports {
    fun sendPending(onDone: (UploadOutcome) -> Unit)

    fun sendState(onDone: (StateSend) -> Unit)

    suspend fun stateDump(): ByteArray?
}

internal class UploaderHomeReports(
    private val uploader: DiagnosticsUploader,
    private val dumps: StateDumpSource,
) : HomeReports {
    override fun sendPending(onDone: (UploadOutcome) -> Unit) = uploader.sendDiagnosticsNow(onDone)

    override fun sendState(onDone: (StateSend) -> Unit) = uploader.sendStateNow(onDone)

    override suspend fun stateDump(): ByteArray? = dumps.gzipped()
}

object NoHomeReports : HomeReports {
    override fun sendPending(onDone: (UploadOutcome) -> Unit) = onDone(UploadOutcome(sent = 0, kept = 0))

    override fun sendState(onDone: (StateSend) -> Unit) = onDone(StateSend.NotSignedIn)

    override suspend fun stateDump(): ByteArray? = null
}
