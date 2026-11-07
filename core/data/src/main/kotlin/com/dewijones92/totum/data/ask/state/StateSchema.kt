package com.dewijones92.totum.data.ask.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class Describe(val text: String)

@OptIn(ExperimentalSerializationApi::class)
@Suppress("TooManyFunctions")
public object StateSchema {

    public fun of(descriptor: SerialDescriptor, sample: JsonElement? = null): JsonObject = buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        node(descriptor, sample, descriptor.describedAs()).forEach { (key, value) -> put(key, value) }
    }

    private fun node(descriptor: SerialDescriptor, sample: JsonElement?, text: String?): JsonObject {
        val body = body(descriptor, sample)
        return buildJsonObject {
            body.forEach { (key, value) ->
                if (key == "type" && descriptor.isNullable) {
                    put(key, nullable(value))
                } else {
                    put(key, value)
                }
            }
            (text ?: descriptor.describedAs())?.let { put("description", it) }
        }
    }

    private fun nullable(type: JsonElement): JsonArray = buildJsonArray {
        add(type)
        add(JsonPrimitive("null"))
    }

    private fun body(descriptor: SerialDescriptor, sample: JsonElement?): JsonObject = when {
        descriptor.serialName.startsWith(FREE_FORM_PREFIX) -> buildJsonObject {}
        descriptor.isInline -> body(descriptor.getElementDescriptor(0), sample)
        else -> byKind(descriptor, sample)
    }

    private fun byKind(descriptor: SerialDescriptor, sample: JsonElement?): JsonObject = when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> typed("string")
        PrimitiveKind.BOOLEAN -> typed("boolean")
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> typed("number")
        is PrimitiveKind -> typed("integer")
        SerialKind.ENUM -> enumOf(descriptor)
        StructureKind.LIST -> listOf(descriptor, sample)
        StructureKind.MAP -> mapOf(descriptor, sample)
        StructureKind.CLASS, StructureKind.OBJECT -> classOf(descriptor, sample as? JsonObject)
        is PolymorphicKind, SerialKind.CONTEXTUAL -> buildJsonObject {}
    }

    private fun typed(type: String) = buildJsonObject { put("type", type) }

    private fun enumOf(descriptor: SerialDescriptor) = buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { descriptor.elementNames.forEach { add(JsonPrimitive(it)) } })
    }

    private fun listOf(descriptor: SerialDescriptor, sample: JsonElement?) = buildJsonObject {
        put("type", "array")
        val rows = (sample as? JsonArray)?.takeIf { it.isNotEmpty() }
        put("items", node(descriptor.getElementDescriptor(0), rows?.firstOrNull(), null))
        (sample as? JsonArray)?.let { put(COUNT, it.size) }
    }

    private fun mapOf(descriptor: SerialDescriptor, sample: JsonElement?) = buildJsonObject {
        put("type", "object")
        put("additionalProperties", node(descriptor.getElementDescriptor(1), null, null))
        (sample as? JsonObject)?.let { put(COUNT, it.size) }
    }

    private fun classOf(descriptor: SerialDescriptor, sample: JsonObject?) = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                for (i in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(i)
                    put(
                        name,
                        node(
                            descriptor.getElementDescriptor(i),
                            sample?.get(name)?.takeUnless { it is JsonNull },
                            descriptor.getElementAnnotations(i).describedAs(),
                        ),
                    )
                }
            },
        )
        put(
            "required",
            buildJsonArray {
                for (i in 0 until descriptor.elementsCount) {
                    if (!descriptor.isElementOptional(i)) add(JsonPrimitive(descriptor.getElementName(i)))
                }
            },
        )
    }

    private fun SerialDescriptor.describedAs(): String? = annotations.describedAs()

    private fun List<Annotation>.describedAs(): String? = filterIsInstance<Describe>().firstOrNull()?.text

    private const val COUNT = "x-count"
    private const val FREE_FORM_PREFIX = "kotlinx.serialization.json."
}
