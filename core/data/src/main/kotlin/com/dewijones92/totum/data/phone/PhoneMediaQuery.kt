package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaKind

public enum class PhoneSort { NEWEST, OLDEST, TITLE, LONGEST, LARGEST }

public data class PhoneMediaQuery(
    val kind: PhoneMediaKind,
    val folder: String? = null,
    val search: String = "",
    val sort: PhoneSort = PhoneSort.NEWEST,
    val offset: Int = 0,
    val limit: Int = PAGE_SIZE,
) {
    public fun nextOffset(returned: Int): Int? = if (returned >= limit) offset + returned else null

    public companion object {
        public const val PAGE_SIZE: Int = 100
    }
}

public data class PhoneMediaPage(
    val entries: List<PhoneMediaEntry>,
    val next: Int?,
)

public data class PhoneFolder(val name: String, val count: Int)

public interface PhoneMediaSource {
    public suspend fun page(query: PhoneMediaQuery): PhoneMediaPage

    public suspend fun folders(kind: PhoneMediaKind): List<PhoneFolder>
}
