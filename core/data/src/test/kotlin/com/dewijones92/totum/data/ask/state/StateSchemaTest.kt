package com.dewijones92.totum.data.ask.state

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateSchemaTest {

    @Serializable
    enum class Mood {
        @SerialName("calm")
        CALM,

        @SerialName("loud")
        LOUD,
    }

    @Serializable
    @Describe("One thing in a shop.")
    data class Thing(
        @Describe("What it is called.")
        val name: String,
        val price: Double,
        val stock: Int?,
        val mood: Mood,
        val tags: List<String> = emptyList(),
        val shelf: Map<String, Int> = emptyMap(),
        val extra: JsonObject = JsonObject(emptyMap()),
    )

    @Serializable
    data class Shop(
        @Describe("Everything for sale.")
        val things: List<Thing>,
        val open: Boolean,
    )

    private val sample = Json.parseToJsonElement(
        """{"things":[{"name":"a","price":1.5,"stock":null,"mood":"calm","tags":["x"],"shelf":{"top":1,"low":2}},""" +
            """{"name":"b","price":2.0,"stock":3,"mood":"loud"}],"open":true}""",
    )

    private val schema = StateSchema.of(Shop.serializer().descriptor, sample)

    private fun property(path: String): JsonObject =
        path.split('.').fold(schema) { node, name ->
            if (name == "[]") {
                node.getValue("items").jsonObject
            } else {
                node.getValue("properties").jsonObject.getValue(name).jsonObject
            }
        }

    @Test
    fun `a class becomes an object with typed properties and descriptions`() {
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        assertEquals("Everything for sale.", property("things").getValue("description").jsonPrimitive.content)
        assertEquals("boolean", property("open").getValue("type").jsonPrimitive.content)
        assertEquals("What it is called.", property("things.[].name").getValue("description").jsonPrimitive.content)
        assertEquals(
            "One thing in a shop.",
            property("things").getValue("items").jsonObject["description"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `lists carry the count of rows actually in the data`() {
        assertEquals(2, property("things").getValue("x-count").jsonPrimitive.int)
        assertEquals(1, property("things.[].tags").getValue("x-count").jsonPrimitive.int)
        assertEquals(2, property("things.[].shelf").getValue("x-count").jsonPrimitive.int)
    }

    @Test
    fun `a nullable field says so and a defaulted one is not required`() {
        val types = property("things.[].stock").getValue("type").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("integer", "null"), types)
        val required = property("things").getValue("items").jsonObject.getValue("required").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("name" in required)
        assertFalse("tags" in required)
    }

    @Test
    fun `an enum lists its wire names and numbers are told apart`() {
        val values = property("things.[].mood").getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("calm", "loud"), values)
        assertEquals("number", property("things.[].price").getValue("type").jsonPrimitive.content)
        assertEquals("object", property("things.[].shelf").getValue("type").jsonPrimitive.content)
        assertEquals(
            "integer",
            property("things.[].shelf").getValue("additionalProperties").jsonObject
                .getValue("type").jsonPrimitive.content
        )
    }

    @Test
    fun `free-form json is left open`() {
        assertTrue(property("things.[].extra").keys.none { it == "properties" })
    }

    @Test
    fun `the real app state schema describes every list and carries counts`() {
        val built = AppStateBuilder.build(StateTestData.inputs())
        val real = StateSchema.of(AppState.serializer().descriptor, built.json)
        val props = real.getValue("properties").jsonObject
        assertTrue(props.keys.containsAll(listOf("queue", "history", "downloads", "listens", "events", "settings")))
        assertTrue(props.values.all { it.jsonObject.containsKey("description") })
        val items = props.getValue("queue").jsonObject.getValue("properties").jsonObject.getValue("items").jsonObject
        assertEquals(built.counts.getValue("queue"), items.getValue("x-count").jsonPrimitive.int)
        assertTrue(real.toString().length < MAX_SCHEMA_CHARS)
        assertTrue(real.getValue("required") is JsonArray)
        assertTrue(
            real.getValue("properties").jsonObject.getValue("history").jsonObject
                .getValue("items").jsonObject.getValue("properties").jsonObject.containsKey("played")
        )
        assertTrue(
            items.getValue("items").jsonObject.getValue("properties").jsonObject.getValue("played")
                .jsonObject.getValue("type").jsonPrimitive.content == "boolean"
        )
        assertFalse(items.getValue("items").jsonObject.getValue("required").jsonArray.isEmpty())
    }

    private companion object {
        const val MAX_SCHEMA_CHARS = 40_000
    }
}
