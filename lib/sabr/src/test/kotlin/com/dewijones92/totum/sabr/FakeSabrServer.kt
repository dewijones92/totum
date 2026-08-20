package com.dewijones92.totum.sabr

/**
 * The SABR server, faked: it answers each fetch in turn and keeps every request it was sent.
 *
 * One class because there were five — `AskedTimes`, `Fake`, `Replaying`, `Recording`,
 * `RecordingRanges` — each a fake transport replaying a list of responses and differing only in
 * which field of the request it happened to record. Two of them also carried their own copy of the
 * `player_time_ms` decoder, and the copies **disagreed**: one returned `0` when the field was
 * absent and the other `-1`, so the same missing field was a pass in one file and a fail in the
 * other. Five answers to one question with the copies already diverging is the exact shape the
 * repo's DRY law exists to stop, and it is no less a defect for living in the test source set.
 *
 * So: the transport records request BODIES, and what a request said is read off them by the
 * decoders below. A test asserts on `timesAsked` or `rangesAsked` and never touches protobuf.
 */
internal class FakeSabrServer(private val answer: (Int) -> ByteArray) : SabrTransport {

    /** Replays [responses] in order, then answers nothing — an exhausted server. */
    constructor(responses: List<ByteArray>) : this({ at -> responses.getOrElse(at) { ByteArray(0) } })

    /** Every request body, in order. The raw material for the decoders below. */
    val requests: MutableList<ByteArray> = mutableListOf()

    /** The `player_time_ms` each fetch claimed — the field most of these tests are about. */
    val timesAsked: List<Long> get() = requests.map(::playerTimeMsIn)

    /** The buffered ranges each fetch described. */
    val rangesAsked: List<List<DescribedRange>> get() = requests.map(::bufferedRangesIn)

    override suspend fun post(url: String, body: ByteArray): ByteArray {
        requests += body
        return answer(requests.size - 1)
    }
}

/** A [BufferedRange] as the fields under test, so an assertion reads as prose rather than protobuf. */
internal data class DescribedRange(
    val startTimeMs: Long,
    val durationMs: Long,
    val startSegment: Int,
    val endSegment: Int,
)

/**
 * The `player_time_ms` a request carries — inside `ClientAbrState` as field 28, which
 * `VideoPlaybackAbrRequestTest` pins as the only place the server reads it from.
 *
 * `-1` when absent, deliberately: a real claim can legitimately be `0`, so returning `0` for
 * "no such field" makes a missing field indistinguishable from the start of a video. That was the
 * disagreement between the two copies this replaces.
 */
internal fun playerTimeMsIn(body: ByteArray): Long {
    val state = Protobuf.read(body)[CLIENT_ABR_STATE]
        ?.filterIsInstance<Protobuf.Value.Bytes>()
        ?.firstOrNull()
        ?: return ABSENT
    return Protobuf.read(state.value).number(PLAYER_TIME_MS)
}

/** The buffered ranges a request describes, in the order it lists them. */
internal fun bufferedRangesIn(body: ByteArray): List<DescribedRange> =
    Protobuf.read(body)[BUFFERED_RANGES]
        ?.filterIsInstance<Protobuf.Value.Bytes>()
        ?.map { Protobuf.read(it.value) }
        ?.map { range ->
            DescribedRange(
                startTimeMs = range.number(RANGE_START_TIME_MS),
                durationMs = range.number(RANGE_DURATION_MS),
                startSegment = range.number(RANGE_START_SEGMENT).toInt(),
                endSegment = range.number(RANGE_END_SEGMENT).toInt(),
            )
        }
        ?: emptyList()

private fun Map<Int, List<Protobuf.Value>>.number(field: Int): Long =
    (this[field]?.firstOrNull() as? Protobuf.Value.Number)?.value ?: ABSENT

private const val ABSENT = -1L

private const val CLIENT_ABR_STATE = 1
private const val BUFFERED_RANGES = 3
private const val PLAYER_TIME_MS = 28

private const val RANGE_START_TIME_MS = 2
private const val RANGE_DURATION_MS = 3
private const val RANGE_START_SEGMENT = 4
private const val RANGE_END_SEGMENT = 5
