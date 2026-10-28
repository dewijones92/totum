package com.dewijones92.totum.innertube.feeds

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public object SeenProgress {
    private val seen = MutableStateFlow<Map<String, AccountProgress>>(emptyMap())
    public val positions: StateFlow<Map<String, AccountProgress>> = seen.asStateFlow()

    public fun saw(found: Map<String, AccountProgress>) {
        if (found.isNotEmpty()) seen.update { it + found }
    }

    public fun reset() {
        seen.value = emptyMap()
    }
}
