package com.dewijones92.totum.reminders

public object Waveforms {
    public fun once(pulses: LongArray): LongArray = longArrayOf(0) + pulses

    public fun repeated(pulses: LongArray, times: Int, gapMs: Long): LongArray =
        (1..times).fold(longArrayOf(0)) { wave, i -> if (i == 1) wave + pulses else wave + gapMs + pulses }

    public fun vibratingMs(waveform: LongArray): Long = waveform.filterIndexed { i, _ -> i % 2 == 1 }.sum()
}
