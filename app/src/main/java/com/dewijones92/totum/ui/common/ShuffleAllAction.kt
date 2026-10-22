package com.dewijones92.totum.ui.common

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.shuffle.ShuffleAll
import com.dewijones92.totum.shuffle.ShuffleOutcome
import com.dewijones92.totum.shuffle.ShuffleTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val SHUFFLE_ALL_TAG = "shuffle-all"

@Composable
fun ShuffleAllAction(
    shuffleAll: ShuffleAll,
    scope: CoroutineScope,
    target: ShuffleTarget,
    wide: Boolean = false,
    tag: String = SHUFFLE_ALL_TAG,
) {
    val loading by shuffleAll.loading.collectAsStateWithLifecycle()
    val loaded = loading[target.key]
    val begin = rememberShuffleStarter(shuffleAll, scope)
    val onTap = { begin(target) }
    val label = loaded?.let { pluralStringResource(R.plurals.shuffle_all_loading, it, it) }
        ?: stringResource(R.string.shuffle_all)
    if (wide) {
        OutlinedButton(
            onClick = onTap,
            enabled = loaded == null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
        ) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(label)
        }
    } else if (loaded != null) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp).testTag(tag))
    } else {
        IconButton(onClick = onTap, modifier = Modifier.testTag(tag)) {
            Icon(Icons.Filled.Shuffle, contentDescription = stringResource(R.string.shuffle_all_of, target.title))
        }
    }
}

@Composable
fun rememberShuffleStarter(shuffleAll: ShuffleAll, scope: CoroutineScope): (ShuffleTarget) -> Unit {
    val context = LocalContext.current.applicationContext
    var asking by remember { mutableStateOf<ShuffleTarget?>(null) }
    var randomStarts by remember { mutableStateOf(LastShuffleChoice.randomStarts) }
    val ask = rememberShuffleAsk()
    val start: (ShuffleTarget, Boolean, Boolean) -> Unit = { target, includeShorts, startAtRandom ->
        asking = null
        LastShuffleChoice.randomStarts = startAtRandom
        scope.launch {
            val outcome = shuffleAll.shuffle(target, includeShorts, startAtRandom)
            val message = when (outcome) {
                ShuffleOutcome.AlreadyLoading -> return@launch
                ShuffleOutcome.Nothing -> context.getString(R.string.shuffle_all_nothing)
                is ShuffleOutcome.Shuffled ->
                    context.resources.getQuantityString(R.plurals.shuffle_all_done, outcome.count, outcome.count) +
                        if (outcome.unfinished.isEmpty()) "" else context.getString(R.string.shuffle_all_incomplete)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    asking?.let { target ->
        ShortsQuestion(target.title, randomStarts, { randomStarts = it }, { asking = null }) { shorts, random ->
            start(target, shorts, random)
        }
    }
    return { target ->
        if (target.sources.any { it is MediaSource.VideoChannel }) asking = target else ask { start(target, false, it) }
    }
}

@Composable
private fun ShortsQuestion(
    title: String,
    randomStarts: Boolean,
    onRandomStarts: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    start: (Boolean, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shuffle_all_shorts_title)) },
        text = {
            Column {
                Text(stringResource(R.string.shuffle_all_shorts_body, title))
                RandomStartsCheckbox(randomStarts, onRandomStarts)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { start(true, randomStarts) }
            ) { Text(stringResource(R.string.shuffle_all_with_shorts)) }
        },
        dismissButton = {
            TextButton(
                onClick = { start(false, randomStarts) }
            ) { Text(stringResource(R.string.shuffle_all_videos_only)) }
        },
    )
}
