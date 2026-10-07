package com.dewijones92.totum.reminders.kit

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.Waveforms

class Buzzer(context: Context, private val tag: String) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    fun buzz(what: String, waveform: LongArray, repeatFrom: Int = -1) {
        Diag.log(
            tag,
            "dewidebug buzz $what: ${Waveforms.vibratingMs(waveform)}ms of ${waveform.sum()}ms, " +
                "repeat=${repeatFrom >= 0}, hasVibrator=${vibrator.hasVibrator()}",
        )
        vibrator.vibrate(
            VibrationEffect.createWaveform(waveform, repeatFrom),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
    }

    fun stop() = vibrator.cancel()
}
