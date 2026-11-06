package com.dewijones92.totum.common

import java.net.URI

/**
 * A validated absolute http(s) URL.
 *
 * Construction only succeeds for well-formed URLs, so any [HttpUrl] in the
 * system is known-good — callers never re-validate. This is the single URL
 * type shared by the app's domain model and standalone libraries; it lives
 * in :lib:common (no app dependencies) so libraries stay publishable.
 */
@JvmInline
public value class HttpUrl private constructor(public val value: String) {

    public val host: String? get() = runCatching { URI(value).host }.getOrNull()

    override fun toString(): String = value

    public companion object {
        /** Returns an [HttpUrl] if [raw] is an absolute http(s) URL, else null. */
        public fun parse(raw: String): HttpUrl? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val isHttp = uri.scheme == "http" || uri.scheme == "https"
            return if (isHttp && !uri.host.isNullOrBlank()) HttpUrl(uri.toString()) else null
        }

        /** Like [parse] but throws [IllegalArgumentException]; for trusted inputs. */
        public fun of(raw: String): HttpUrl =
            requireNotNull(parse(raw)) { "Not an absolute http(s) URL: $raw" }
    }
}
