package com.dewijones92.totum.ytdlp.chaquopy

import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.contract.YtDlpEngineContract

class ChaquopyYtDlpEngineContractTest : YtDlpEngineContract() {
    override fun newEngine(): YtDlpEngine = ChaquopyYtDlpEngine(ApplicationProvider.getApplicationContext())
}
