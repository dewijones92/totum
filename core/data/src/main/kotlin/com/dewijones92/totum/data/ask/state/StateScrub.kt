package com.dewijones92.totum.data.ask.state

import com.dewijones92.totum.common.ReportRedactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public object StateScrub {

    public data class Scrubbed(val element: JsonElement, val droppedKeys: Int, val redactedValues: Int)

    public fun clean(element: JsonElement, redactor: ReportRedactor = IDENTIFIERS): Scrubbed {
        val tally = Tally()
        val cleaned = walk(element, redactor, tally)
        return Scrubbed(cleaned, tally.keys, tally.values)
    }

    public val IDENTIFIERS: ReportRedactor = ReportRedactor(keepIdentifiers = true)

    private class Tally(var keys: Int = 0, var values: Int = 0)

    private fun walk(element: JsonElement, redactor: ReportRedactor, tally: Tally): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.filterNot { (key, _) -> ReportRedactor.isSecretKey(key).also { if (it) tally.keys++ } }
                .associate { (key, value) -> key to walk(value, redactor, tally) },
        )
        is JsonArray -> JsonArray(element.map { walk(it, redactor, tally) })
        is JsonPrimitive -> if (element.isString) {
            val cleaned = redactor.redact(element.content)
            if (cleaned != element.content) tally.values++
            JsonPrimitive(cleaned)
        } else {
            element
        }
    }
}
