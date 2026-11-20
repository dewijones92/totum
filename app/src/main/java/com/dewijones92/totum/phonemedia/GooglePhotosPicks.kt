package com.dewijones92.totum.phonemedia

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.google.GooglePhotosPicker
import com.dewijones92.totum.data.phone.google.PickerState
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GooglePhotosPicks(
    val picker: GooglePhotosPicker,
    private val operations: Operations,
    private val scope: CoroutineScope,
    private val subject: String = "Google Photos",
) {
    @Volatile
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true || picker.state.value.busy) {
            Diag.log("google-photos", "a pick is already running; the tap changed nothing")
            return
        }
        Diag.log("google-photos", "a pick was started from the Google Photos tab")
        job = scope.launch {
            operations.track("google-photos-pick", OperationKind.IMPORT_PHOTOS, subject) { progress ->
                val end = picker.pick { found, step, steps ->
                    progress.report(found = found, step = step, steps = steps)
                }
                when (end) {
                    is PickerState.Finished -> OperationEnd.Done(end.imported)
                    is PickerState.Failed -> OperationEnd.Failed(end.reason)
                    PickerState.Expired ->
                        OperationEnd.Failed("⌛ The Google Photos page timed out before anything was chosen")
                    PickerState.NeedsSetup -> OperationEnd.Failed("⚙️ Google Photos is not set up in this build")
                    else -> OperationEnd.Nothing
                }
            }
        }
    }

    fun cancel() {
        Diag.log("google-photos", "the pick was cancelled from the Google Photos tab")
        job?.cancel()
    }
}
