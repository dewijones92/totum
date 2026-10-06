package com.dewijones92.totum.ytdlp.chaquopy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

internal class NodeIsolate : JsIsolate {
    private val process = ProcessBuilder(node!!, "-e", LOOP).redirectErrorStream(false).start()
    private val input = process.outputStream.bufferedWriter()
    private val output = process.inputStream.bufferedReader()
    var closed = false
        private set

    override fun evaluate(code: String): String {
        input.write(JsonObject(mapOf("code" to JsonPrimitive(code))).toString())
        input.newLine()
        input.flush()
        val reply = Json.parseToJsonElement(checkNotNull(output.readLine()) { "node exited" }).jsonObject
        val value = reply.getValue("value").jsonPrimitive.content
        check(reply.getValue("ok").jsonPrimitive.boolean) { value }
        return value
    }

    override fun close() {
        closed = true
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    companion object {
        val node: String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "node") }.firstOrNull { it.canExecute() }?.path

        private const val LOOP = """
const vm = require('vm');
require('readline').createInterface({ input: process.stdin }).on('line', (line) => {
  let reply;
  try { reply = { ok: true, value: String(vm.runInThisContext(JSON.parse(line).code)) }; }
  catch (e) { reply = { ok: false, value: String(e) }; }
  process.stdout.write(JSON.stringify(reply) + '\n');
});
"""
    }
}

internal class NodeIsolates : JsIsolates {
    val opened = mutableListOf<NodeIsolate>()
    override fun usable() = true
    override fun open(): JsIsolate = NodeIsolate().also { opened += it }
    override fun close() = opened.forEach { it.close() }
}
