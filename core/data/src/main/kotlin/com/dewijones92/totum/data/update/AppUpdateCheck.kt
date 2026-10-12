package com.dewijones92.totum.data.update

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

public sealed interface UpdateStatus {
    public data object NotChecked : UpdateStatus

    public data class NotApplicable(val installed: String) : UpdateStatus

    public data class Unknown(val installed: AppVersion, val detail: String) : UpdateStatus

    public data class UpToDate(val installed: AppVersion, val latest: AppVersion) : UpdateStatus

    public data class OutOfDate(val installed: AppVersion, val latest: Release) : UpdateStatus
}

public class AppUpdateCheck(
    private val installedName: String,
    private val source: ReleaseSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val installed: AppVersion? = AppVersion.parse(installedName)
    private val mutableStatus = MutableStateFlow(
        if (installed == null) UpdateStatus.NotApplicable(installedName) else UpdateStatus.NotChecked,
    )
    public val status: StateFlow<UpdateStatus> = mutableStatus.asStateFlow()

    @Volatile private var lastCheckedAtMs: Long? = null

    @Volatile private var lastProblem: String? = null

    public suspend fun checkNow(reason: String): UpdateStatus {
        val version = installed ?: return mutableStatus.value.also {
            Diag.log(TAG, "not checking for an update ($reason): installed \"$installedName\" is not a release build")
        }
        lastCheckedAtMs = now()
        val verdict = when (val lookup = source.latest()) {
            is ReleaseLookup.Found -> {
                lastProblem = null
                judge(version, lookup.release)
            }
            is ReleaseLookup.Unavailable -> {
                lastProblem = lookup.detail
                when (val previous = mutableStatus.value) {
                    is UpdateStatus.OutOfDate, is UpdateStatus.UpToDate -> previous
                    else -> UpdateStatus.Unknown(version, lookup.detail)
                }
            }
        }
        val problem = lastProblem?.let { " [lookup failed: $it]" }.orEmpty()
        Diag.log(TAG, "checked ($reason): installed=$version -> ${describe(verdict)}$problem")
        mutableStatus.value = verdict
        return verdict
    }

    public suspend fun keepChecking(interval: Duration) {
        checkNow("at launch")
        while (true) {
            delay(interval)
            checkNow("every $interval")
        }
    }

    public fun diagnostics(): Map<String, String> = buildMap {
        put("update.installed", installedName)
        put("update.status", describe(mutableStatus.value))
        put("update.checkedAtMs", lastCheckedAtMs?.toString() ?: "never")
        put("update.lastProblem", lastProblem ?: "none")
    }

    private companion object {
        const val TAG = "update"

        fun judge(installed: AppVersion, latest: Release): UpdateStatus =
            if (latest.version > installed) {
                UpdateStatus.OutOfDate(installed, latest)
            } else {
                UpdateStatus.UpToDate(installed, latest.version)
            }

        fun describe(status: UpdateStatus): String = when (status) {
            UpdateStatus.NotChecked -> "not checked yet"
            is UpdateStatus.NotApplicable -> "not a release build (${status.installed})"
            is UpdateStatus.Unknown -> "unknown: ${status.detail}"
            is UpdateStatus.UpToDate -> "up to date (latest release v${status.latest})"
            is UpdateStatus.OutOfDate ->
                "OUT OF DATE: v${status.latest.version} is out " +
                    "(published ${status.latest.publishedAt ?: "at an unknown time"})"
        }
    }
}
