package com.dewijones92.totum.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.data.digest.DigestService
import com.dewijones92.totum.operations.Operations

class DigestHost(val service: DigestService, val operations: Operations?)

val LocalDigests = staticCompositionLocalOf<DigestHost?> { null }
