package com.dewijones92.totum.data.diagnostics

import com.dewijones92.totum.common.ReportRedactor
import com.dewijones92.totum.data.ask.state.AppStateBuilder
import com.dewijones92.totum.data.ask.state.AppStateInputs
import com.dewijones92.totum.data.ask.state.StateJson
import com.dewijones92.totum.data.ask.state.StateScrub
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupCodec
import com.dewijones92.totum.data.backup.BackupReadResult
import com.dewijones92.totum.data.backup.toBackup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

public object StateDump {

    public const val FORMAT: Int = 1
    public const val KIND: String = "totum-state-dump"

    public val CREDENTIAL_FILES: List<String> = listOf(
        "youtube_account",
        "totum_visitor",
        "totum_screen",
        "totum_drive_backup",
        "totum_test_holds",
        "totum_diagnostics",
    )

    public class Built(
        public val json: JsonObject,
        public val gzipped: ByteArray,
        public val counts: Map<String, Int>,
        public val skippedFiles: List<String>,
        public val droppedKeys: Int,
        public val redactedValues: Int,
    )

    public fun build(
        given: AppStateInputs,
        preferences: Map<String, Map<String, Any?>>,
        installId: String = "",
    ): Built {
        val (kept, skipped) = preferences.entries.partition { (file, _) -> !isCredentialFile(file) }
        val credentials = skipped.flatMap { it.value.values }.filterIsInstance<String>()
        val inputs = given.copy(secrets = given.secrets + credentials)
        val state = AppStateBuilder.build(inputs)
        val backup = inputs.backup.copy(queue = inputs.queue.entries.map { it.item.toBackup() })
        val root = JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "kind" to JsonPrimitive(KIND),
                "takenAtMs" to JsonPrimitive(inputs.nowMs),
                "appVersion" to JsonPrimitive(inputs.appVersion),
                "installId" to JsonPrimitive(installId),
                "backup" to StateJson.parseToJsonElement(BackupCodec.encode(backup)),
                "state" to state.json,
                "preferences" to preferencesJson(kept.associate { it.key to it.value }),
            ),
        )
        val scrubbed = StateScrub.clean(root, ReportRedactor(inputs.secrets, inputs.hosts, keepIdentifiers = true))
        val json = scrubbed.element as JsonObject
        return Built(
            json = json,
            gzipped = packText(json.toString()),
            counts = state.counts,
            skippedFiles = skipped.map { it.key }.sorted(),
            droppedKeys = scrubbed.droppedKeys + state.droppedKeys,
            redactedValues = scrubbed.redactedValues + state.redactedValues,
        )
    }

    public fun unpack(gzipped: ByteArray): JsonObject =
        GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .let { StateJson.parseToJsonElement(it) as JsonObject }

    public fun packText(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
            .toByteArray()

    public fun backupOf(dump: JsonObject): Backup? {
        val section = dump["backup"] ?: return null
        return (BackupCodec.decode(section.toString()) as? BackupReadResult.Ok)?.backup
    }

    private fun isCredentialFile(name: String): Boolean =
        CREDENTIAL_FILES.any { name == it || name.startsWith("${it}_") }

    private fun preferencesJson(files: Map<String, Map<String, Any?>>): JsonObject =
        JsonObject(files.toSortedMap().mapValues { (_, values) -> fileJson(values) })

    private fun fileJson(values: Map<String, Any?>): JsonObject =
        JsonObject(values.toSortedMap().mapValues { (key, value) -> valueJson(key, value) })

    private fun valueJson(key: String, value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive(null as String?)
        is String -> AppStateBuilder.settings(mapOf(key to value))[key] ?: JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Collection<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) }.sortedBy { it.content })
        else -> JsonPrimitive(value.toString())
    }
}
