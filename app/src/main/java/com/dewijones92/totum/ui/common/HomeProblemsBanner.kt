package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.home.HomeProblem
import com.dewijones92.totum.data.home.HomeSeverity

@Composable
fun HomeProblemsBanner(problems: List<HomeProblem>, padStatusBar: Boolean, modifier: Modifier = Modifier) {
    if (problems.isEmpty()) return
    val broken = problems.any { it.severity == HomeSeverity.BROKEN }
    Surface(
        color = if (broken) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (broken) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
        modifier = modifier.fillMaxWidth().testTag(HOME_PROBLEMS_BANNER_TAG),
    ) {
        Column(
            Modifier
                .then(if (padStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.home_problems_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            problems.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

const val HOME_PROBLEMS_BANNER_TAG = "home-problems-banner"
