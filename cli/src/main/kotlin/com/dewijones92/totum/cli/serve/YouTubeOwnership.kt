package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.Requester
import kotlinx.serialization.json.Json
import java.io.File

internal sealed interface Admission {
    data object Accepted : Admission
    data class Refused(val why: String) : Admission
}

internal class YouTubeOwnership(private val file: File) {

    fun owner(): Requester? = runCatching {
        file.takeIf { it.isFile }?.let { Json.decodeFromString(Requester.serializer(), it.readText()) }
    }.onFailure { Diag.warn("home", "the record of who signed the Pi in to YouTube is unreadable", it) }.getOrNull()

    fun admit(from: Requester?): Admission {
        val current = owner()
        return when {
            from == null -> refuse(
                "❌ This Totum cannot say which phone it is; update it before it signs the home server in",
                current,
                null,
            )
            current == null -> {
                record(from)
                Diag.log("home", "${from.label} (${from.id}) is now the phone the Pi is signed in to YouTube from")
                Admission.Accepted
            }
            current.id == from.id -> {
                if (current.label != from.label) record(from)
                Admission.Accepted
            }
            else -> refuse(
                "🔒 The home server is signed in to YouTube from another phone (${current.label}); " +
                    "sign that one out first",
                current,
                from,
            )
        }
    }

    fun mayForget(by: String?): Boolean {
        val current = owner() ?: return true
        val allowed = by != null && by == current.id
        if (!allowed) {
            Diag.warn(
                "home",
                "YouTube sign-out refused: asked by ${by ?: "an unidentified phone"}, " +
                    "the Pi is signed in from ${current.label} (${current.id})",
            )
        }
        return allowed
    }

    fun clear() {
        file.delete()
        Diag.log("home", "nobody is recorded as the phone the Pi is signed in to YouTube from")
    }

    private fun record(from: Requester) {
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(Requester.serializer(), from))
    }

    private fun refuse(why: String, current: Requester?, from: Requester?): Admission {
        Diag.warn(
            "home",
            "YouTube sign-in handover refused: from=${from?.label}(${from?.id}) " +
                "owner=${current?.label}(${current?.id})",
        )
        return Admission.Refused(why)
    }
}
