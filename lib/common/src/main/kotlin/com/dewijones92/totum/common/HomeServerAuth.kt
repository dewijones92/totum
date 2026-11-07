package com.dewijones92.totum.common

public object HomeServerAuth {
    public const val HEADER: String = "X-Totum-Token"

    @Volatile
    private var host: () -> String? = { null }

    @Volatile
    private var token: () -> String = { "" }

    @Volatile
    private var sinkHost: () -> String? = { null }

    public fun use(host: () -> String?, token: () -> String, sinkHost: () -> String? = { null }) {
        this.host = host
        this.token = token
        this.sinkHost = sinkHost
    }

    public fun homeHost(): String? = host()

    public fun reportSinkHost(): String? = sinkHost()?.takeIf { it.isNotBlank() }

    public fun reportEndpoint(): String? = reportSinkHost()?.let { "https://$it/ingest" }

    public fun signedIn(): Boolean = isSignedIn(host(), token())

    public fun isSignedIn(host: String?, token: String): Boolean = !host.isNullOrBlank() && token.isNotBlank()

    public fun headersFor(requestHost: String?): Map<String, String> {
        if (requestHost == null) return emptyMap()
        val ours = listOfNotNull(host(), reportSinkHost())
        if (ours.none { it.equals(requestHost, ignoreCase = true) }) return emptyMap()
        val value = token().takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(HEADER to value)
    }
}
