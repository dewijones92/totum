package com.dewijones92.totum.common

public object HomeServerAuth {
    public const val HEADER: String = "X-Totum-Token"

    @Volatile
    private var host: () -> String? = { null }

    @Volatile
    private var token: () -> String = { "" }

    public fun use(host: () -> String?, token: () -> String) {
        this.host = host
        this.token = token
    }

    public fun headersFor(requestHost: String?): Map<String, String> {
        val home = host() ?: return emptyMap()
        if (requestHost == null || !requestHost.equals(home, ignoreCase = true)) return emptyMap()
        val value = token().takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(HEADER to value)
    }
}
