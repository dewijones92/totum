package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.PhoneFolder
import com.dewijones92.totum.data.phone.PhoneMediaBrowser
import com.dewijones92.totum.data.phone.PhoneMediaListState
import com.dewijones92.totum.data.phone.PhoneMediaQuery
import com.dewijones92.totum.data.phone.PhoneMediaSource
import com.dewijones92.totum.data.phone.PhoneSort
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.PhoneMediaKind
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import kotlinx.coroutines.delay

internal class PhoneControlsState(
    search: String,
    folder: String?,
    sort: PhoneSort,
) {
    var search by mutableStateOf(search)
    var folder by mutableStateOf(folder)
    var sort by mutableStateOf(sort)

    fun query(kind: PhoneMediaKind) = PhoneMediaQuery(kind, folder = folder, search = search, sort = sort)
}

internal class PhoneBrowse(
    val browser: PhoneMediaBrowser,
    val state: PhoneMediaListState,
    val controls: PhoneControlsState,
    val folders: List<PhoneFolder>,
    val retry: () -> Unit,
)

private const val SEARCH_DEBOUNCE_MS = 300L

@Composable
internal fun rememberPhoneBrowse(
    container: AppContainer,
    kind: PhoneMediaKind,
    source: PhoneMediaSource,
    subject: String,
): PhoneBrowse {
    val browser = remember(kind, source) { PhoneMediaBrowser(source) }
    val state by browser.state.collectAsStateWithLifecycle()
    var search by rememberSaveable(kind) { mutableStateOf("") }
    var folder by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var sort by rememberSaveable(kind) { mutableStateOf(PhoneSort.NEWEST.name) }
    val controls = remember(kind) { PhoneControlsState(search, folder, PhoneSort.valueOf(sort)) }
    LaunchedEffect(controls.search, controls.folder, controls.sort) {
        search = controls.search
        folder = controls.folder
        sort = controls.sort.name
    }
    val folders by produceState(emptyList<PhoneFolder>(), kind, source) {
        value = runCatching { source.folders(kind) }
            .onFailure { Diag.warn("phone-media", "folders of $kind could not be listed", it) }
            .getOrDefault(emptyList())
    }
    var attempt by remember(kind) { mutableIntStateOf(0) }
    LaunchedEffect(kind, source, controls.search, controls.folder, controls.sort, attempt) {
        if (controls.search.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        container.operations.track("phone-media:${kind.key}", OperationKind.OPEN_PAGE, subject) {
            val opened = browser.open(controls.query(kind))
            val failure = browser.state.value.failure
            if (failure != null) OperationEnd.Failed(failure) else OperationEnd.Done(opened)
        }
    }
    return PhoneBrowse(browser, state, controls, folders, retry = { attempt++ })
}

@Composable
internal fun PhoneControls(kind: PhoneMediaKind, browse: PhoneBrowse, modifier: Modifier = Modifier) {
    val controls = browse.controls
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = controls.search,
            onValueChange = { controls.search = it },
            label = { Text(stringResource(R.string.phone_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(PHONE_SEARCH_TAG),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortsFor(kind), key = { it.name }) { sort ->
                FilterChip(
                    selected = controls.sort == sort,
                    onClick = {
                        Diag.log("phone-media", "sort $kind by $sort")
                        controls.sort = sort
                    },
                    label = { Text(stringResource(sort.label())) },
                )
            }
        }
        if (browse.folders.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all-folders") {
                    FilterChip(
                        selected = controls.folder == null,
                        onClick = { controls.folder = null },
                        label = { Text(stringResource(R.string.phone_folder_all)) },
                    )
                }
                items(browse.folders, key = { it.name }) { folder ->
                    FilterChip(
                        selected = controls.folder == folder.name,
                        onClick = {
                            Diag.log("phone-media", "folder $kind \"${folder.name}\" (${folder.count} files)")
                            controls.folder = folder.name
                        },
                        label = { Text("${folder.name} · ${folder.count}") },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PhoneListNotes(browse: PhoneBrowse) {
    val state = browse.state
    state.failure?.let {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.phone_failed, it), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = browse.retry) { Text(stringResource(R.string.phone_retry)) }
        }
    }
    if (state.empty) {
        val searching = browse.controls.search.isNotBlank()
        Text(
            stringResource(if (searching) R.string.phone_empty_search else R.string.phone_empty),
            Modifier.padding(20.dp),
        )
    }
}

private fun sortsFor(kind: PhoneMediaKind): List<PhoneSort> = when (kind) {
    PhoneMediaKind.PHOTO -> listOf(PhoneSort.NEWEST, PhoneSort.OLDEST, PhoneSort.LARGEST)
    else -> listOf(PhoneSort.NEWEST, PhoneSort.OLDEST, PhoneSort.TITLE, PhoneSort.LONGEST, PhoneSort.LARGEST)
}

private fun PhoneSort.label(): Int = when (this) {
    PhoneSort.NEWEST -> R.string.phone_sort_newest
    PhoneSort.OLDEST -> R.string.phone_sort_oldest
    PhoneSort.TITLE -> R.string.phone_sort_title
    PhoneSort.LONGEST -> R.string.phone_sort_longest
    PhoneSort.LARGEST -> R.string.phone_sort_largest
}

const val PHONE_SEARCH_TAG = "phone-media-search"
