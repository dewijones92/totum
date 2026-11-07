package com.dewijones92.totum.diagnostics

import android.os.Build
import com.dewijones92.totum.BuildConfig
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.GitHubReport
import com.dewijones92.totum.common.ReportRedactor

internal object GitHubReportSources {

    private const val MAX_EVENTS = 80

    fun facts(): List<Pair<String, String>> = listOf(
        GitHubReport.VERSION to
            "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, commit ${BuildConfig.GIT_SHA}, " +
            "${BuildConfig.BUILD_TYPE})",
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "ABIs" to Build.SUPPORTED_ABIS.joinToString(),
    )

    fun events(): List<String> = Breadcrumbs.snapshot().takeLast(MAX_EVENTS).map { entry ->
        "${Breadcrumbs.formatTime(entry.atEpochMs)} [${entry.tag}] ${entry.message}"
    }
}

internal fun reportRedactorFor(
    homeServerBase: String,
    homeServerToken: String,
    prowlarrKey: String,
    builtInBase: String = BuildConfig.HOME_SERVER,
): ReportRedactor {
    val bases = listOf(homeServerBase, builtInBase).map { it.trim() }.filter { it.isNotEmpty() }
    val hosts = bases.flatMap { listOf(it, "totum.$it", "totumauth.$it", "crashlog.$it") }
    return ReportRedactor(secrets = listOf(homeServerToken, prowlarrKey), hosts = hosts)
}
