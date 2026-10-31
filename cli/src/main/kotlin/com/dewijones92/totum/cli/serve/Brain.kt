package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.filing.FilePlan
import com.dewijones92.totum.cli.serve.filing.FileRequest
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest

internal interface Brain {
    suspend fun suggest(request: AskRequest): AskAnswer
    suspend fun vet(request: VetRequest): VetAnswer
    suspend fun file(request: FileRequest): FilePlan = throw BrainFailure("this brain does not file")
}

internal open class BrainFailure(message: String) : Exception(message)

internal class NotSignedIn : BrainFailure("The home server isn't signed in to Claude yet")
