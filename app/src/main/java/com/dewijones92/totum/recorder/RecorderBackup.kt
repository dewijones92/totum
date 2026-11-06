package com.dewijones92.totum.recorder

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra

internal fun encodeChoices(choices: RecorderChoices): String =
    listOfNotNull(
        "mic".takeIf { choices.mic },
        "face".takeIf { choices.face },
        "taps".takeIf { choices.taps },
    ).joinToString(",")

internal fun decodeChoices(text: String): RecorderChoices {
    val on = text.split(",").map { it.trim() }.toSet()
    return RecorderChoices(mic = "mic" in on, face = "face" in on, taps = "taps" in on)
}

internal fun BackupService.BackupSettings.withRecorderChoices(store: RecorderChoiceStore): BackupService.BackupSettings =
    withExtra(
        key = KEY,
        tag = "recorder",
        what = "recorder choices",
        read = { encodeChoices(store.read()) },
        apply = { text -> store.write(decodeChoices(text)) },
    )

private const val KEY = "recorder.v1"
