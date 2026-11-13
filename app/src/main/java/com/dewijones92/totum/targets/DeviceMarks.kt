package com.dewijones92.totum.targets

import com.dewijones92.totum.data.targets.PlayOn
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetColours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DeviceMark(val deviceId: String, val label: String, val colour: Long)

object DeviceMarkRules {

    fun marks(
        playingOn: Map<String, Target>,
        options: List<PlayOnOption>,
        colours: Map<String, Long>,
        phoneItemId: String?,
    ): Map<String, DeviceMark> {
        if (options.size <= 1) return emptyMap()
        val result = LinkedHashMap<String, DeviceMark>()
        if (phoneItemId != null) result[phoneItemId] = markOf(PlayOn.phone, colours)
        playingOn.forEach { (itemId, target) -> result[itemId] = markOf(target, colours) }
        return result
    }

    private fun markOf(target: Target, colours: Map<String, Long>): DeviceMark = DeviceMark(
        target.id,
        "${target.kind.label.substringBefore(' ')} ${target.label}",
        colours[target.id] ?: TargetColours.PHONE,
    )
}

class DeviceMarks(targets: TargetsRepository, phoneItemId: Flow<String?>, scope: CoroutineScope) {
    val marks: StateFlow<Map<String, DeviceMark>> = combine(
        targets.playingOn,
        targets.options,
        targets.colours,
        phoneItemId,
    ) { playingOn, options, colours, phone ->
        DeviceMarkRules.marks(playingOn, options, colours, phone)
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
}
