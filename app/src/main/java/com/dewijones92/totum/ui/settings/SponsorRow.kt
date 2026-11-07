package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.BuildConfig
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.SponsorLink
import com.dewijones92.totum.ui.common.rememberLinkOpener

@Composable
internal fun SponsorRow(configured: String = BuildConfig.SPONSOR_URL) {
    val link = remember(configured) { SponsorLink.of(configured) }
    LaunchedEffect(link) {
        Diag.log(
            "sponsor",
            if (link == null) {
                "sponsor button hidden: no usable https link in this build (${configured.length} chars configured)"
            } else {
                "sponsor button shown for ${link.host}"
            },
        )
    }
    if (link == null) return
    val open = rememberLinkOpener()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                Diag.log("sponsor", "sponsor page opened (${link.host})")
                open(link.value)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = stringResource(R.string.sponsor_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.sponsor_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
