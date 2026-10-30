package com.dewijones92.totum.dailyalarms

public object QuickPresets {
    public const val STAIRS: String = "🏃 Run up and down the stairs ×5"
    public val FIRST: List<String> = listOf(STAIRS)
}

public fun List<String>.usedPreset(title: String): List<String> = remembering(title, keep = Int.MAX_VALUE)

public fun List<String>.addedPreset(title: String): List<String> = remembering(title, keep = Int.MAX_VALUE)

public fun List<String>.renamedPreset(old: String, new: String): List<String> {
    val clean = new.trim()
    if (clean.isEmpty()) return this
    return mapNotNull {
        when {
            it == old -> clean
            it.equals(clean, ignoreCase = true) -> null
            else -> it
        }
    }
}

public fun List<String>.deletedPreset(title: String): List<String> = filterNot { it == title }
