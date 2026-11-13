package com.dewijones92.totum.phoneuse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.ui.settings.QuestCard

@Composable
fun UsageAccessCard(granted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    QuestCard(Icons.Outlined.VerifiedUser, stringResource(R.string.phone_use_access_title), modifier) {
        Text(
            stringResource(if (granted) R.string.phone_use_access_on else R.string.phone_use_access_off),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("phone-use-access"),
        )
        Text(
            stringResource(R.string.phone_use_access_why),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                Diag.log(PhoneUse.TAG, "dewidebug phoneuse opening usage access settings granted=$granted")
                context.startActivity(usageAccessIntent(context))
            },
            modifier = Modifier.testTag("phone-use-access-open"),
        ) { Text(stringResource(R.string.phone_use_access_open)) }
        if (!granted) {
            Text(
                stringResource(R.string.phone_use_access_restricted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
