package com.dewijones92.totum.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
            if (account.isSignedIn()) _state.value = UiState.SignedIn
        }
    }

    fun signIn() {
        if (loginJob?.isActive == true) return
        _state.value = UiState.Starting
        loginJob = viewModelScope.launch {
            account.signIn().collect { event ->
                _state.value = event.toUiState()
                // As soon as sign-in lands, load the live subscriptions so the
                // rest of the app reflects it without a restart.
                if (event is DeviceLoginEvent.Succeeded) accountSubscriptions.refresh(force = true)
            }
        }
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        _state.value = if (_state.value is UiState.SignedIn) UiState.SignedIn else UiState.SignedOut
    }

    fun signOut() {
        loginJob?.cancel()
        viewModelScope.launch {
            account.signOut()
            _state.value = UiState.SignedOut
            // Clear the live subscription list (and the feed) app-wide.
            accountSubscriptions.refresh(force = true)
        }
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
