package com.dewijones92.totum.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal fun mainThreadScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
