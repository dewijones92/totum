package com.dewijones92.totum.ui.stats

import com.dewijones92.totum.stats.Bar
import com.dewijones92.totum.stats.TopEntry

internal class PhoneUseActions(
    val onByTime: (Boolean) -> Unit,
    val onTrendDays: (Int) -> Unit,
    val onBar: (Bar) -> Unit,
    val onApp: (TopEntry) -> Unit,
    val appLabel: (String) -> String,
)
