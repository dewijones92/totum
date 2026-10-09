package com.dewijones92.totum.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.pins.Pin

internal fun LazyListScope.pinnedSection() {
    item(key = "pinned") {
        val pins = LocalPins.current ?: return@item
        if (pins.pinned.isEmpty()) return@item
        Column {
            Text(
                text = stringResource(R.string.pinned_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pins.pinned.asReversed(), key = { it.key }) { pin -> PinCard(pin, pins) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinCard(pin: Pin, pins: PinActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .width(CARD_WIDTH)
                .combinedClickable(onClick = { pins.play(pin) }, onLongClick = { menu = true })
                .padding(vertical = 4.dp),
        ) {
            MediaThumbnail(
                url = pin.artUrl?.let(HttpUrl::parse),
                contentDescription = pin.title,
                modifier = Modifier.size(CARD_WIDTH),
            )
            Text(text = pin.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.pin_home)) }, onClick = {
                menu = false
                pins.addToHomeScreen(pin)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.pin_unpin)) }, onClick = {
                menu = false
                pins.toggle(pin)
            })
        }
    }
}

private val CARD_WIDTH = 112.dp
