package com.dewijones92.totum.common

public object HomeServerHosts {
    public fun app(base: String): String = "totum.$base"

    public fun sink(base: String): String = "crashlog.$base"

    public fun auth(base: String): String = "totumauth.$base"

    public fun allFor(bases: Collection<String>): List<String> =
        bases.map { it.trim() }.filter { it.isNotEmpty() }.flatMap { listOf(it, app(it), auth(it), sink(it)) }
}
