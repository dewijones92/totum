package com.dewijones92.totum.phoneuse

import com.dewijones92.totum.stats.NudgeBlock
import com.dewijones92.totum.stats.RawUsage

data class NudgeRequest(val sinceLastMs: Long, val unlocksToday: Int, val shownAt: Long)

class PhoneUseContext(
    val exsurgeActive: () -> Boolean,
    val alarmRinging: () -> Boolean,
    val kidLocked: () -> Boolean,
    val videoPlaying: () -> Boolean,
    val ownPackage: String,
)

interface PhoneUsePorts {
    fun accessGranted(): Boolean
    fun usageEvents(fromMs: Long, toMs: Long): List<RawUsage>
    fun screenOn(): Boolean
    fun inCall(): Boolean
    fun appBlock(packageName: String?): NudgeBlock?
    fun listenForUnlocks(onUnlock: () -> Unit): AutoCloseable
    fun showNudge(request: NudgeRequest)
    fun hideNudge()
}

object NoPhoneUsePorts : PhoneUsePorts {
    override fun accessGranted() = false
    override fun usageEvents(fromMs: Long, toMs: Long) = emptyList<RawUsage>()
    override fun screenOn() = true
    override fun inCall() = false
    override fun appBlock(packageName: String?): NudgeBlock? = null
    override fun listenForUnlocks(onUnlock: () -> Unit) = AutoCloseable { }
    override fun showNudge(request: NudgeRequest) = Unit
    override fun hideNudge() = Unit
}
