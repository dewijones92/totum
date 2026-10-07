package com.dewijones92.totum.reminders.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import com.dewijones92.totum.common.Diag

class AlarmTone(private val context: Context, private val tag: String) {
    private var ringing: Ringtone? = null

    fun start() {
        if (ringing?.isPlaying == true) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val tone = RingtoneManager.getRingtone(context, uri)
        if (tone == null) {
            Diag.warn(tag, "dewidebug alarm tone: no ringtone for $uri")
            return
        }
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        tone.isLooping = true
        tone.play()
        ringing = tone
        Diag.log(tag, "dewidebug alarm tone started uri=$uri playing=${tone.isPlaying}")
    }

    fun stop() {
        ringing?.stop()
        ringing = null
        Diag.log(tag, "dewidebug alarm tone stopped")
    }
}
