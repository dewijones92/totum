package com.dewijones92.totum.domain

public enum class ChosenWay(public val diagName: String) {
    TAP("tap"),
    PEEK("peek"),
    LINK("link"),
    PIN("pin"),
    SHORTS_REEL("shorts-reel"),
    ASK_CARD("ask-card"),
    QUEUE_ROW("queue-row"),
    BACK_OR_FORWARD("back-or-forward"),
    SWITCH("switch"),
    SHUFFLE("shuffle"),
    NOTIFICATION("notification"),
}

public sealed interface PlayReason {
    public val diagName: String

    public data class Chosen(val way: ChosenWay) : PlayReason {
        override val diagName: String get() = "chosen-${way.diagName}"
    }

    public data class Next(val group: String?) : PlayReason {
        override val diagName: String get() = "next-in-queue"
    }

    public data class Previous(val group: String?) : PlayReason {
        override val diagName: String get() = "previous-in-queue"
    }

    public data object RepeatOne : PlayReason {
        override val diagName: String get() = "repeat-one"
    }

    public data class RepeatGroup(val group: String?) : PlayReason {
        override val diagName: String get() = "repeat-group"
    }

    public data object RepeatQueue : PlayReason {
        override val diagName: String get() = "repeat-queue"
    }

    public data class Explore(val relatedTo: String?) : PlayReason {
        override val diagName: String get() = "explore"
    }

    public data class RelatedAfterQueue(val relatedTo: String?) : PlayReason {
        override val diagName: String get() = "related-after-queue"
    }

    public data class Radio(val station: String?) : PlayReason {
        override val diagName: String get() = "radio"
    }

    public data object NewUpload : PlayReason {
        override val diagName: String get() = "new-upload"
    }
}

public fun PlayReason.describe(): String {
    val detail = when (this) {
        is PlayReason.Next -> group
        is PlayReason.Previous -> group
        is PlayReason.RepeatGroup -> group
        is PlayReason.Explore -> relatedTo
        is PlayReason.RelatedAfterQueue -> relatedTo
        is PlayReason.Radio -> station
        is PlayReason.Chosen, PlayReason.RepeatOne, PlayReason.RepeatQueue, PlayReason.NewUpload -> return diagName
    }
    return "$diagName[${detail ?: "none"}]"
}
