package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.PhoneMediaKind

public enum class KindAccess {
    FULL,
    PARTIAL,
    NONE,
    ;

    public val readable: Boolean get() = this != NONE
}

public data class PhoneMediaAccess(
    val video: KindAccess,
    val audio: KindAccess,
    val photos: KindAccess,
) {
    public fun of(kind: PhoneMediaKind): KindAccess = when (kind) {
        PhoneMediaKind.VIDEO -> video
        PhoneMediaKind.AUDIO -> audio
        PhoneMediaKind.PHOTO -> photos
    }

    public val anyReadable: Boolean get() = video.readable || audio.readable || photos.readable

    public val allFull: Boolean
        get() = video == KindAccess.FULL && audio == KindAccess.FULL && photos == KindAccess.FULL

    public val partialOnly: Boolean
        get() = listOf(video, photos).any { it == KindAccess.PARTIAL }

    public fun describe(): String = "video=$video audio=$audio photos=$photos"

    public companion object {
        public val NONE: PhoneMediaAccess = PhoneMediaAccess(KindAccess.NONE, KindAccess.NONE, KindAccess.NONE)
    }
}

public sealed interface PhoneMediaGate {
    public data class Open(val access: PhoneMediaAccess) : PhoneMediaGate

    public data object NeedsAsk : PhoneMediaGate

    public data object Blocked : PhoneMediaGate
}

public object PhoneMediaPermissions {
    public const val READ_VIDEO: String = "android.permission.READ_MEDIA_VIDEO"
    public const val READ_AUDIO: String = "android.permission.READ_MEDIA_AUDIO"
    public const val READ_IMAGES: String = "android.permission.READ_MEDIA_IMAGES"
    public const val READ_SELECTED: String = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    public val toAsk: List<String> = listOf(READ_VIDEO, READ_AUDIO, READ_IMAGES, READ_SELECTED)

    public fun access(granted: (String) -> Boolean): PhoneMediaAccess {
        val selected = granted(READ_SELECTED)
        fun visual(permission: String) = when {
            granted(permission) -> KindAccess.FULL
            selected -> KindAccess.PARTIAL
            else -> KindAccess.NONE
        }
        return PhoneMediaAccess(
            video = visual(READ_VIDEO),
            audio = if (granted(READ_AUDIO)) KindAccess.FULL else KindAccess.NONE,
            photos = visual(READ_IMAGES),
        )
    }

    public fun gate(access: PhoneMediaAccess, askedBefore: Boolean): PhoneMediaGate = when {
        access.anyReadable -> PhoneMediaGate.Open(access)
        askedBefore -> PhoneMediaGate.Blocked
        else -> PhoneMediaGate.NeedsAsk
    }

    public fun reason(gate: PhoneMediaGate, access: PhoneMediaAccess): String = when (gate) {
        is PhoneMediaGate.Open -> "open (${access.describe()})"
        PhoneMediaGate.NeedsAsk -> "never asked, nothing granted (${access.describe()})"
        PhoneMediaGate.Blocked ->
            "asked before and nothing granted, so only Settings can change it (${access.describe()})"
    }
}
