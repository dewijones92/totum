package com.dewijones92.totum.sabr

import com.dewijones92.totum.sabr.Protobuf.bytesAt
import com.dewijones92.totum.sabr.Protobuf.numberAt

internal data class FormatInitialization(
    val itag: Int?,
    val endTimeMs: Long?,
    val endSegment: Long?,
    val durationUnits: Long?,
    val timescale: Long?,
) {
    fun endOfSegmentMs(sequence: Int): Long? {
        val end = endTimeMs?.takeIf { it > 0 } ?: return null
        val segments = endSegment?.takeIf { it > 0 } ?: return null
        if (sequence < 0 || sequence > segments) return null
        return sequence * end / segments
    }

    override fun toString(): String =
        "itag=$itag endTimeMs=$endTimeMs endSegment=$endSegment durationUnits=$durationUnits timescale=$timescale"

    companion object {
        fun parse(payload: ByteArray): FormatInitialization {
            val fields = Protobuf.read(payload)
            val formatId = fields.bytesAt(FIELD_FORMAT_ID)?.let(Protobuf::read)
            return FormatInitialization(
                itag = formatId?.numberAt(FORMAT_ID_ITAG)?.toInt(),
                endTimeMs = fields.numberAt(FIELD_END_TIME_MS),
                endSegment = fields.numberAt(FIELD_END_SEGMENT),
                durationUnits = fields.numberAt(FIELD_DURATION_UNITS),
                timescale = fields.numberAt(FIELD_TIMESCALE),
            )
        }

        private const val FIELD_FORMAT_ID = 2
        private const val FIELD_END_TIME_MS = 3
        private const val FIELD_END_SEGMENT = 4
        private const val FIELD_DURATION_UNITS = 9
        private const val FIELD_TIMESCALE = 10
        private const val FORMAT_ID_ITAG = 1
    }
}
