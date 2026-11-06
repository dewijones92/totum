package com.dewijones92.totum.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.HttpDownloadStrategy
import com.dewijones92.totum.data.update.ApkVerdict
import com.dewijones92.totum.data.update.RELEASE_APK_NAME
import com.dewijones92.totum.data.update.Release
import com.dewijones92.totum.data.update.UpdateStatus
import com.dewijones92.totum.data.update.apkDownload
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

class AppUpdateInstaller(
    private val context: Context,
    client: OkHttpClient,
    private val operations: Operations,
    private val scope: CoroutineScope,
    updates: StateFlow<UpdateStatus>,
    private val notices: UpdateNotices = UpdateNotices(context),
) {
    private val strategy = HttpDownloadStrategy(client)
    private val inspector = ApkInspector(context)
    private val mutableState = MutableStateFlow<UpdateInstall>(UpdateInstall.Idle)
    val state: StateFlow<UpdateInstall> = mutableState.asStateFlow()

    @Volatile private var job: Job? = null

    init {
        scope.launch {
            updates.collect { status ->
                if (status is UpdateStatus.UpToDate) withContext(Dispatchers.IO) { tidy(keep = null) }
            }
        }
    }

    fun start(release: Release) {
        if (job?.isActive == true) {
            Diag.log(TAG, "download asked for v${release.version} while one is already running; not started twice")
            return
        }
        job = scope.launch { fetch(release) }
    }

    fun cancel() {
        Diag.log(TAG, "download cancelled from the banner (state=${mutableState.value.summary})")
        job?.cancel()
    }

    fun install(from: Context): Boolean {
        val ready = mutableState.value as? UpdateInstall.Ready ?: return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            Diag.log(TAG, "install: Android has not yet allowed installs from Totum; opening that setting")
            mutableState.value = ready.copy(allowAsked = true)
            open(from, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            return false
        }
        val file = fileFor(ready.release)
        if (!file.isFile) {
            Diag.warn(TAG, "install: ${file.name} is gone from the cache; downloading again")
            mutableState.value = UpdateInstall.Idle
            start(ready.release)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_SUFFIX", file)
        Diag.log(TAG, "install: handing ${file.name} (${file.length()}B) to Android's installer")
        return open(
            from,
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun diagnostics(): Map<String, String> = mapOf("update.install" to mutableState.value.summary)

    private fun open(from: Context, intent: Intent): Boolean = runCatching {
        from.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Diag.warn(TAG, "install: nothing could open ${intent.action}", it) }.isSuccess

    private suspend fun fetch(release: Release) {
        val item = release.apkDownload() ?: return fail(release, "the release has no $RELEASE_APK_NAME to download")
        val target = fileFor(release)
        withContext(Dispatchers.IO) { tidy(keep = target) }
        val already = withContext(Dispatchers.IO) {
            target.isFile && inspector.inspect(target, release) is ApkVerdict.Genuine
        }
        if (already) {
            Diag.log(TAG, "download: ${target.name} is already here and checks out; not fetched again")
            return ready(release)
        }
        withContext(Dispatchers.IO) { target.delete() }
        Diag.log(TAG, "download: fetching v${release.version} from ${release.apk?.url} (${release.apk?.sizeBytes}B)")
        mutableState.value = UpdateInstall.Downloading(release, null)
        val failure = download(release, item, target)
        if (failure != null) fail(release, failure) else settle(release, target)
    }

    private suspend fun download(release: Release, item: PlayableItem, target: File): String? {
        var failure: String? = null
        try {
            operations.track(KEY, OperationKind.TASK, item.item.title) { progress ->
                progress.report(step = 0, steps = PERCENT)
                var lastPercent = -1
                strategy.download(item, target, audioOnly = false).collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> {
                            val percent = state.fraction?.let { (it * PERCENT).toInt() }
                            progress.report(step = percent ?: 0, steps = PERCENT)
                            mutableState.value = UpdateInstall.Downloading(release, percent)
                            if (percent != null && percent != lastPercent) {
                                lastPercent = percent
                                notices.downloading(release, percent)
                            }
                        }
                        is DownloadState.Failed -> failure = state.reason
                        else -> Unit
                    }
                }
                failure?.let { OperationEnd.Failed(it) } ?: OperationEnd.Done(1)
            }
        } catch (cancelled: CancellationException) {
            Diag.log(TAG, "download: v${release.version} stopped; the part-file stays for next time")
            mutableState.value = UpdateInstall.Idle
            notices.stopped()
            throw cancelled
        }
        return failure
    }

    private suspend fun settle(release: Release, target: File) {
        when (val verdict = withContext(Dispatchers.IO) { inspector.inspect(target, release) }) {
            ApkVerdict.Genuine -> ready(release)
            is ApkVerdict.Refused -> {
                withContext(Dispatchers.IO) { target.delete() }
                Diag.warn(TAG, "download: v${release.version} REFUSED, not handed to Android: ${verdict.why}")
                mutableState.value = UpdateInstall.Refused(release, verdict.why)
                notices.refused(release, verdict.why)
            }
        }
    }

    private fun ready(release: Release) {
        Diag.log(TAG, "download: v${release.version} is whole and matches the installed app's package and key")
        mutableState.value = UpdateInstall.Ready(release)
        notices.ready(release)
    }

    private fun fail(release: Release, why: String) {
        Diag.warn(TAG, "download: v${release.version} failed: $why")
        mutableState.value = UpdateInstall.Failed(release, why)
        notices.failed(release, why)
    }

    private fun fileFor(release: Release) = File(folder(), "totum-${release.version}.apk")

    private fun folder(): File = File(context.cacheDir, FOLDER).also { it.mkdirs() }

    private fun tidy(keep: File?) {
        val removed = folder().listFiles().orEmpty()
            .filter { it.name != keep?.name && it.name != "${keep?.name}.part" }
            .count { it.delete() }
        if (removed > 0) Diag.log(TAG, "tidy: removed $removed old update file(s)")
    }

    companion object {
        const val PROVIDER_SUFFIX = ".updates"
        private const val TAG = "update"
        private const val KEY = "app-update"
        private const val FOLDER = "app-update"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val PERCENT = 100
    }
}
