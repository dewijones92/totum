package com.dewijones92.totum.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.dewijones92.totum.common.Diag

@Composable
internal fun <T : Any> ListThenDetail(
    detail: T?,
    listKey: String,
    onBack: (() -> Unit)? = null,
    list: @Composable () -> Unit,
    shown: @Composable (T) -> Unit,
) {
    val places = rememberSaveableStateHolder()
    val showing = detail != null
    LaunchedEffect(listKey, showing) {
        if (showing) Diag.log("place", "$listKey kept while a page is open over it")
    }
    BackHandler(enabled = showing && onBack != null) {
        Diag.log("nav", "back closes the page over $listKey")
        onBack?.invoke()
    }
    if (detail == null) places.SaveableStateProvider(listKey, list) else shown(detail)
}

@Composable
internal fun KeepsEachPlace(key: String, content: @Composable () -> Unit) =
    rememberSaveableStateHolder().SaveableStateProvider(key, content)
