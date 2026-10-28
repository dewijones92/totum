package com.dewijones92.totum.ui.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.data.backup.BackupCodec
import com.dewijones92.totum.data.backup.BackupReadResult
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.importexport.ImportOutcome
import com.dewijones92.totum.importexport.ImportSummary
import com.dewijones92.totum.importexport.SubscriptionImporter
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.showing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Import / Export screen: runs an import off the main thread and
 * exposes its progress/result, and builds the OPML export on demand. File IO
 * (reading the picked file, writing the export) stays in the composable, which
 * owns the `Context`; this holds no Android types.
 */
class ImportExportViewModel(
    private val backup: BackupService,
    private val messages: Messages,
    private val importer: SubscriptionImporter,
    val signedIn: StateFlow<Boolean>,
    private val operations: Operations? = null,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Working : State
        data class Imported(val summary: ImportSummary) : State
        data class Failed(val detail: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun import(content: String) {
        _state.value = State.Working
        // Default dispatcher: parsing (DOM/JSON over a whole export file) is CPU work
        // that must not block the main thread; downstream network calls switch to IO themselves.
        viewModelScope.launch(Dispatchers.Default) {
            _state.value = when (val outcome = importer.import(content)) {
                is ImportOutcome.Done -> State.Imported(outcome.summary)
                is ImportOutcome.ParseError -> State.Failed(outcome.detail)
            }
        }
    }

    suspend fun buildOpml(): String = operations.showing("export-opml", messages.exporting) {
        withContext(Dispatchers.Default) { importer.exportOpml() }
    }

    suspend fun buildBackup(): String = operations.showing("backup-file", messages.backingUp) {
        val made = backup.create()
        withContext(Dispatchers.Default) { BackupCodec.encode(made) }
    }

    /**
     * Applies a backup file and returns what to tell the user. A message rather than a
     * state because there is nothing to keep afterwards — the result is the library
     * itself, which every screen is already observing.
     */
    suspend fun restore(content: String): String = operations.showing("restore-file", messages.restoring) {
        when (val read = withContext(Dispatchers.Default) { BackupCodec.decode(content) }) {
            is BackupReadResult.Unreadable -> messages.unreadable
            is BackupReadResult.TooNew -> messages.tooNew
            is BackupReadResult.Ok -> backup.restore(read.backup).let {
                messages.restored(it.subscriptions, it.playlists, it.progressEntries)
            }
        }
    }

    /** Strings supplied by the screen, so this stays free of Android resources. */
    class Messages(
        val unreadable: String,
        val tooNew: String,
        val restored: (Int, Int, Int) -> String,
        val restoring: String,
        val backingUp: String,
        val exporting: String,
    )

    fun clearResult() {
        _state.value = State.Idle
    }

    companion object {
        /** [messages] comes from the screen, which owns the string resources. */
        fun factory(container: AppContainer, messages: Messages): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ImportExportViewModel(
                    backup = container.backupService,
                    messages = messages,
                    importer = container.subscriptionImporter,
                    signedIn = container.accountSubscriptions.signedIn,
                    operations = container.operations,
                )
            }
        }
    }
}
