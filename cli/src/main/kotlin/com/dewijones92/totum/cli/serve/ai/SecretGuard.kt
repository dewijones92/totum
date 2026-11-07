package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.common.ReportRedactor

internal object SecretGuard {

    private val SECRET_NAME = Regex("(?i)(token|secret|passw|credential|api[_-]?key|auth(orization)?$|_key$)")
    private const val MIN_LENGTH = 8

    fun leaks(text: String, env: Map<String, String> = System.getenv()): List<String> {
        val named = env.filter { (name, value) -> SECRET_NAME.containsMatchIn(name) && value.length >= MIN_LENGTH }
            .filter { (_, value) -> text.contains(value) }.keys.toList()
        val shaped = if (ReportRedactor.looksSecret(text)) listOf("a value shaped like a secret") else emptyList()
        return named + shaped
    }
}
