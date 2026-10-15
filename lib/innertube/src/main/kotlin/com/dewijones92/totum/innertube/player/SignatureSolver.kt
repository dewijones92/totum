package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import java.net.URLEncoder

public fun interface SignatureSolver {
    public suspend fun solve(challenges: List<String>, playerUrl: String): Map<String, String>
}

public suspend fun StreamingData.withSolvedSignatures(solver: SignatureSolver, playerUrl: String): StreamingData {
    val challenges = formats.mapNotNull { it.cipher?.signature }.distinct()
    if (challenges.isEmpty()) return this
    val solved = runCatching { solver.solve(challenges, playerUrl) }.getOrElse { failure ->
        Diag.warn("resolve", "could not solve ${challenges.size} signature(s)", failure)
        emptyMap()
    }
    val signed = formats.mapNotNull { format ->
        val cipher = format.cipher ?: return@mapNotNull format
        val answer = solved[cipher.signature] ?: return@mapNotNull null
        format.copy(url = cipher.signedWith(answer) ?: return@mapNotNull null, cipher = null)
    }
    Diag.log(
        "resolve",
        "solved ${solved.size}/${challenges.size} signature(s) — " +
            "${formats.size - signed.size} ciphered format(s) dropped, ${signed.size} of ${formats.size} kept",
    )
    return copy(formats = signed)
}

public suspend fun StreamingData.withSolvedChallenges(
    nSolver: NSolver,
    signatureSolver: SignatureSolver,
    playerUrl: String,
): StreamingData = withSolvedSignatures(signatureSolver, playerUrl).withSolvedN(nSolver, playerUrl)

private fun SignatureCipher.signedWith(answer: String): HttpUrl? {
    val separator = if ('?' in url.value) "&" else "?"
    return HttpUrl.parse(url.value + separator + parameter + "=" + URLEncoder.encode(answer, Charsets.UTF_8))
}
