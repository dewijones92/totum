package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState

@Composable
internal fun homeStateText(job: HomeJob): String = homeStateText(job, onPhone = false)

@Composable
internal fun homeStateText(job: HomeJob, onPhone: Boolean): String = when (job.state) {
    HomeJobState.WAITING -> stringResource(R.string.home_state_waiting)
    HomeJobState.DOWNLOADING -> job.totalBytes?.takeIf { it > 0 }
        ?.let { stringResource(R.string.home_state_downloading, (job.bytes * PERCENT / it).toInt()) }
        ?: stringResource(R.string.home_state_starting)
    HomeJobState.DONE -> stringResource(if (onPhone) R.string.home_state_done_on_phone else R.string.home_state_done)
    HomeJobState.FAILED -> stringResource(R.string.home_state_failed, job.error ?: "")
}

private const val PERCENT = 100L
