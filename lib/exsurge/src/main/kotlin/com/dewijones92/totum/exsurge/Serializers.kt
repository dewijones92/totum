package com.dewijones92.totum.exsurge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("exsurge.Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant): Unit = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}

internal object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("exsurge.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate): Unit = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

internal object DayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("exsurge.DayOfWeek", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: DayOfWeek): Unit = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): DayOfWeek = DayOfWeek.valueOf(decoder.decodeString())
}
