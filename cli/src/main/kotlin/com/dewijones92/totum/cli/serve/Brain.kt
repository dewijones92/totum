package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest

internal interface Brain {
    suspend fun suggest(request: AskRequest): AskAnswer
    suspend fun vet(request: VetRequest): VetAnswer
}

internal class BrainFailure(message: String) : Exception(message)
