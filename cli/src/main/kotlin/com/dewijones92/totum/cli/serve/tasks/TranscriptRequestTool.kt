package com.dewijones92.totum.cli.serve.tasks

import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.digest.TranscriptAskRequest
import com.dewijones92.totum.data.digest.TranscriptDigestRequest
import com.dewijones92.totum.data.digest.TranscriptText
import com.dewijones92.totum.data.transcript.TranscriptFormat
import com.dewijones92.totum.data.transcript.TranscriptParser
import java.io.File

fun main(args: Array<String>) {
    require(args.size >= REQUIRED) { "usage: <captions.vtt> <title> <rolling:true|false> [question]" }
    val cues = TranscriptParser.parse(
        File(args[0]).readText(),
        TranscriptFormat.VTT,
        rolling = args[ROLLING].toBoolean()
    )
    val blocks = TranscriptText.blocks(cues)
    val duration = ((cues.lastOrNull()?.endMs ?: 0L) / MS).toInt()
    val question = args.getOrNull(QUESTION)
    val rendered = question?.let { TranscriptText.forQuestion(blocks, it) } ?: TranscriptText.render(blocks)
    System.err.println("${cues.size} cues -> ${blocks.size} blocks, ${rendered.length} chars, ${duration}s")
    println(
        if (question == null) {
            AskJson.encodeToString(
                TranscriptDigestRequest.serializer(),
                TranscriptDigestRequest(args[1], durationSec = duration, transcript = rendered),
            )
        } else {
            AskJson.encodeToString(
                TranscriptAskRequest.serializer(),
                TranscriptAskRequest(args[1], duration, question, rendered),
            )
        },
    )
}

private const val MS = 1000L
private const val REQUIRED = 3
private const val ROLLING = 2
private const val QUESTION = 3
