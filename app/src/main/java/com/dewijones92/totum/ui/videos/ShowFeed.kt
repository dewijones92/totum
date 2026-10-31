package com.dewijones92.totum.ui.videos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.stats.UseKind
import com.dewijones92.totum.ui.common.FeedGate

@Composable
internal fun rememberShowFeed(container: AppContainer, gate: FeedGate, viewModel: VideosViewModel): (Boolean) -> Unit {
    val counted = remember(gate) { container.statsLog.counting(UseKind.VIDEOS_SHOWN, gate::show) }
    return remember(counted, viewModel) {
        {
                fetch ->
            counted()
            if (fetch) viewModel.fetchNew() else Diag.log("feed-gate", "videos shown as kept from the last fetch")
        }
    }
}
