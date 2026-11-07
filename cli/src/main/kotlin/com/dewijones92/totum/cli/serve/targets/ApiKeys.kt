package com.dewijones92.totum.cli.serve.targets

private val API_KEY = Regex("""(?i)(api_key=|ApiKey=|Token=")[^&\s"']+""")

internal fun String.withoutApiKeys(): String = replace(API_KEY) { "${it.groupValues[1]}***" }
