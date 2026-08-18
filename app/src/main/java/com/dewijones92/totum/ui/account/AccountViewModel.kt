package com.dewijones92.totum.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.innertube.auth.DeviceLoginEvent
import com.dewijones92.totum.innertube.auth.LoginFailure
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.video.AccountSubscriptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the YouTube sign-in screen off the one [YouTubeAccount] seam. The
 * device-code login is a cold flow; this maps its events to a small sealed
 * UI state and lets the user cancel or retry.
 */
class AccountViewModel(
    private val account: YouTubeAccount,
    private val accountSubscriptions: AccountSubscriptions,
) : ViewModel() {

    sealed interface UiState {
        data object SignedOut : UiState
        data object Starting : UiState
        data class AwaitingUser(val userCode: String, val verificationUrl: HttpUrl) : UiState
        data object SignedIn : UiState
        data class Failed(val reason: FailureReason) : UiState
    }

    enum class FailureReason { DENIED, EXPIRED, NETWORK }

    private val _state = MutableStateFlow<UiState>(UiState.SignedOut)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loginJob: Job? = null

    init {
        viewModelScope.launch {
            val already = account.isSignedIn()
            Diag.log("account", "screen opened — ${if (already) "already signed in" else "signed out"}")
            if (already) _state.value = UiState.SignedIn
        }
    }

    fun signIn() {
        if (loginJob?.isActive == true) {
            // Said out loud rather than returning silently: "I tapped Sign in and nothing happened"
            // was completely undiagnosable when this whole flow logged not one line (2026-08-18).
            Diag.log("account", "sign-in already running — ignoring the tap")
            return
        }
        Diag.log("account", "sign-in starting — asking Google for a device code")
        _state.value = UiState.Starting
        loginJob = viewModelScope.launch {
            account.signIn().collect { event ->
                Diag.log("account", event.describe())
                _state.value = event.toUiState()
                // As soon as sign-in lands, load the live subscriptions so the
                // rest of the app reflects it without a restart.
                if (event is DeviceLoginEvent.Succeeded) accountSubscriptions.refresh(force = true)
            }
        }
    }

    fun cancel() {
        Diag.log("account", "sign-in cancelled by the person")
        loginJob?.cancel()
        loginJob = null
        _state.value = if (_state.value is UiState.SignedIn) UiState.SignedIn else UiState.SignedOut
    }

    fun signOut() {
        Diag.log("account", "signing out — the token is being dropped")
        loginJob?.cancel()
        viewModelScope.launch {
            account.signOut()
            _state.value = UiState.SignedOut
            // Clear the live subscription list (and the feed) app-wide.
            accountSubscriptions.refresh(force = true)
        }
    }

    /**
     * One line per login event, written to be read months later by someone with no context.
     *
     * The user code is deliberately included: it is not a credential — it is the thing the person types
     * into google.com/device — and without it a report cannot tell "we never got a code" from "we got
     * one and nobody approved it". Tokens are absent because [AccessToken] redacts itself.
     */
    private fun DeviceLoginEvent.describe(): String = when (this) {
        is DeviceLoginEvent.AwaitingUser ->
            "waiting for approval — code $userCode to be entered at ${verificationUrl.value}"
        is DeviceLoginEvent.Succeeded -> "signed in; loading the account's subscriptions"
        is DeviceLoginEvent.Failed -> "sign-in failed: $reason"
    }

    private fun DeviceLoginEvent.toUiState(): UiState = when (this) {
        is DeviceLoginEvent.AwaitingUser -> UiState.AwaitingUser(userCode, verificationUrl)
        is DeviceLoginEvent.Succeeded -> UiState.SignedIn
        is DeviceLoginEvent.Failed -> UiState.Failed(reason.toFailureReason())
    }

    private fun LoginFailure.toFailureReason(): FailureReason = when (this) {
        LoginFailure.Denied -> FailureReason.DENIED
        LoginFailure.Expired -> FailureReason.EXPIRED
        is LoginFailure.Network -> FailureReason.NETWORK
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountViewModel(container.youTubeAccount, container.accountSubscriptions) }
        }
    }
}
