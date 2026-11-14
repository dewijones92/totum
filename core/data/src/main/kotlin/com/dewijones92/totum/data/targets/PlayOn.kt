package com.dewijones92.totum.data.targets

public data class PlayOnOption(
    val target: Target,
    val power: TargetPower = TargetPower.ON,
    val switchedOnByHand: Boolean = false,
) {
    val id: String get() = target.id
    val isPhone: Boolean get() = target.kind == TargetKind.PHONE
    val wakesWhenPicked: Boolean get() = power == TargetPower.ASLEEP && !switchedOnByHand
    val hint: PlayOnHint
        get() = when {
            power != TargetPower.ASLEEP -> PlayOnHint.NONE
            switchedOnByHand -> PlayOnHint.SWITCH_ON_BY_HAND
            else -> PlayOnHint.WAKES_WHEN_PICKED
        }
}

public enum class PlayOnHint { NONE, WAKES_WHEN_PICKED, SWITCH_ON_BY_HAND }

public object PlayOn {

    public val phone: Target = Target(TargetColours.PHONE_ID, "This phone", TargetKind.PHONE)

    public fun options(listed: List<TargetState>, atHome: Boolean): List<PlayOnOption> {
        val devices = listed.filter { atHome || it.target.kind.reachableAway }
            .map { PlayOnOption(it.target, it.power, it.switchedOnByHand) }
        return listOf(PlayOnOption(phone)) + devices
    }

    public fun atHome(homeServerAnswered: Boolean, onUnmeteredNetwork: Boolean): Boolean =
        homeServerAnswered && onUnmeteredNetwork

    public fun resolve(lastUsed: String?, options: List<PlayOnOption>): PlayOnOption =
        options.firstOrNull { it.id == lastUsed } ?: options.first()

    public fun destination(lastUsed: String?, options: List<PlayOnOption>): String? =
        resolve(lastUsed, options).takeUnless { it.isPhone }?.id

    public fun playingNow(listed: List<TargetState>): Map<String, Target> = buildMap {
        listed.forEach { state ->
            val itemId = state.current?.item?.itemId
            if (state.playing && itemId != null) putIfAbsent(itemId, state.target)
        }
    }

    public fun why(lastUsed: String?, options: List<PlayOnOption>, atHome: Boolean): String {
        val chosen = resolve(lastUsed, options)
        return when {
            lastUsed == null || lastUsed == chosen.id -> "last used ${chosen.target.label}"
            !atHome -> "last used $lastUsed, but not at home, so ${chosen.target.label}"
            else -> "last used $lastUsed, but it is not listed, so ${chosen.target.label}"
        }
    }
}
