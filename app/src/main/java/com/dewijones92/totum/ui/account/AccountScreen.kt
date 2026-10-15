package com.dewijones92.totum.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.account.AccountViewModel.FailureReason
import com.dewijones92.totum.ui.account.AccountViewModel.UiState
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.CopyableText
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.LinkText
import com.dewijones92.totum.ui.settings.SettingsScreen

/**
 * YouTube sign-in — the SmartTube-style device pairing. Signed out, one
 * button starts the flow; while pairing it shows the code to type at the
 * verification URL; signed in, it offers sign-out. Renders the one
 * [AccountViewModel] state.
 */
@Composable
fun AccountScreen(container: AppContainer, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val viewModel: AccountViewModel = viewModel(factory = AccountViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(container, onBack = { showSettings = false }, modifier = modifier)
    } else {
        AccountContent(
            state = state,
            onSignIn = viewModel::signIn,
            onCancel = viewModel::cancel,
            onSignOut = viewModel::signOut,
            onOpenSettings = { showSettings = true },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
internal fun AccountContent(
    state: UiState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Account is reached from inside Library, so it needs its own way back.
        onBack?.let { BackHeader(stringResource(R.string.destination_account), it) }
        when (state) {
            UiState.SignedOut -> SignedOut(onSignIn)
            UiState.Starting -> Centered { CircularProgressIndicator() }
            is UiState.AwaitingUser -> Pairing(state, onCancel)
            UiState.SignedIn -> SignedIn(onSignOut)
            is UiState.Failed -> Failed(state.reason, onSignIn)
        }
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 24.dp),
        ) { Text(stringResource(R.string.settings)) }
    }
}

/** Fills the space above the action row and centres its content. */
@Composable
private fun ColumnScope.Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun ColumnScope.SignedOut(onSignIn: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.AccountCircle,
        headline = stringResource(R.string.account_signed_out_headline),
        supportingText = stringResource(R.string.account_signed_out_supporting),
        modifier = Modifier.weight(1f),
    )
    ActionButton(stringResource(R.string.account_sign_in), onSignIn)
}

@Composable
private fun ColumnScope.SignedIn(onSignOut: () -> Unit) {
    // Your subscriptions, history and Watch Later are read live from the account
    // (SmartTube-style) — nothing is copied here, so there's no import status.
    EmptyState(
        icon = Icons.Outlined.AccountCircle,
        headline = stringResource(R.string.account_signed_in_headline),
        supportingText = stringResource(R.string.account_signed_in_supporting),
        modifier = Modifier.weight(1f),
    )
    OutlinedActionButton(stringResource(R.string.account_sign_out), onSignOut)
}

@Composable
private fun ColumnScope.Pairing(code: UiState.AwaitingUser, onCancel: () -> Unit) {
    Centered {
        Text(
            text = stringResource(R.string.account_pairing_instructions),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        LinkText(
            url = code.verificationUrl.value,
            label = code.verificationUrl.value.removePrefix("https://"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            CopyableText(
                text = code.userCode,
                what = stringResource(R.string.account_code_noun),
                style = MaterialTheme.typography.displaySmall.copy(
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            )
        }
        Text(
            text = stringResource(R.string.account_pairing_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
    OutlinedActionButton(stringResource(R.string.cancel), onCancel)
}

@Composable
private fun ColumnScope.Failed(reason: FailureReason, onRetry: () -> Unit) {
    val message = when (reason) {
        FailureReason.DENIED -> R.string.account_failed_denied
        FailureReason.EXPIRED -> R.string.account_failed_expired
        FailureReason.NETWORK -> R.string.account_failed_network
    }
    Centered {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    ActionButton(stringResource(R.string.account_try_again), onRetry)
}

@Composable
private fun ColumnScope.ActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(bottom = 32.dp),
    ) { Text(label) }
}

@Composable
private fun ColumnScope.OutlinedActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(bottom = 32.dp),
    ) { Text(label) }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun AccountSignedOutPreview() {
    TotumTheme { AccountContent(UiState.SignedOut, {}, {}, {}, {}) }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun AccountPairingPreview() {
    TotumTheme {
        AccountContent(
            UiState.AwaitingUser("BWM-XHD-XJBG", HttpUrl.of("https://www.google.com/device")),
            {},
            {},
            {},
            {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun AccountSignedInPreview() {
    TotumTheme {
        AccountContent(
            UiState.SignedIn,
            {},
            {},
            {},
            {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun AccountScreenPreview() {
    TotumTheme { AccountScreen(FakeAppContainer()) }
}
