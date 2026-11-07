package com.dewijones92.totum.cli.serve.tasks

import com.dewijones92.totum.cli.serve.ai.CallerTools
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.spoiler.SpoilerAnswer
import com.dewijones92.totum.data.spoiler.SpoilerRequest
import com.dewijones92.totum.data.spoiler.SpoilerRules
import com.dewijones92.totum.data.spoiler.SpoilerVerdict

internal object SpoilerPrompts {

    val SYSTEM = """
        You help a family watch sport without being told who won. You are given the titles of videos from sports
        channels, mostly tennis. For each title, decide whether it gives away anything about the result of a
        match, a round or a tournament, and when it does, rewrite it so that it keeps what the video is and
        hides what happened.

        A title spoils the result when it says or hints at any of these: who won or lost; a score; how many sets
        it took or how long it went; a comeback, an upset or a shock; a retirement or an injury; who is through,
        out, champion or lifting a trophy; or a word that tells how close or dramatic the match was (epic,
        thriller, marathon, classic, brutal, dominant, blockbuster, iconic, unforgettable, insane, crazy, chaotic,
        dramatic, a battle). Writing the winner's name first ("A d. B", "A def. B", "A beats B") spoils it too.

        Many channels list the winner first even in plain titles such as "A vs B Highlights", so the order of two
        names is itself a leak. Whenever a title names two players or teams facing each other, put them in
        alphabetical order of surname joined by " v " (never "vs"), whatever else the title says. When you
        change anything, even only that order, answer spoils true.

        Rewrite a spoiling title this way:
        - Keep the players or teams, the tournament, the year, the round or stage, and what kind of video it is
          (highlights, full match, match point, practice, press conference, draw, behind the scenes).
        - Remove every hint at the result, including the verb that links the names.
        - Two players are named in alphabetical order of surname, joined by " v ": "Alcaraz v Sinner", never
          "Sinner v Alcaraz".
        - Keep every name spelled exactly as it is given. Add nothing that is not in the title: no new facts, no
          emoji, no commentary. The only word you may add is "v".
        - When nothing is left after removing the result, give the tournament and round, or the players and the
          tournament.

        A title that names no two players facing each other and gives nothing away (a draw, a preview, a practice
        session, an interview about the future, a training clip, or a video that is not sport at all) does not
        spoil: answer spoils false and repeat the title unchanged in safeTitle.

        Answer with one verdict per title, using the id you were given. Answer only through the structured
        output.
    """.trimIndent()

    val SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["verdicts"],"properties":{"verdicts":
        {"type":"array","items":{"type":"object","additionalProperties":false,
        "required":["id","spoils","safeTitle"],"properties":{"id":{"type":"string"},"spoils":{"type":"boolean"},
        "safeTitle":{"type":"string"}}}}}}
    """.trimIndent().replace("\n", "")

    fun message(request: SpoilerRequest): String = buildString {
        request.items.forEach { item ->
            val channel = item.channel.takeIf { it.isNotBlank() }?.let { " (channel: $it)" } ?: ""
            appendLine("${item.id}: ${item.title}$channel")
        }
    }

    fun prompt(request: SpoilerRequest) = AiPrompt(
        caller = CallerTools.SPOILER,
        what = "spoiler",
        system = SYSTEM,
        message = message(request),
        schema = SCHEMA,
    )

    fun enforced(request: SpoilerRequest, answer: SpoilerAnswer): SpoilerAnswer {
        val asked = request.items.associateBy { it.id }
        val said = answer.verdicts.filter { it.id in asked }.associateBy { it.id }
        val verdicts = request.items.mapNotNull { item ->
            val verdict = said[item.id]
            if (verdict == null) {
                Diag.warn("spoiler", "item ${item.id}: no verdict from the model; left out, so it stays hidden")
                null
            } else {
                val decided = SpoilerRules.decide(item.title, verdict.spoils, verdict.safeTitle)
                Diag.log(
                    "spoiler",
                    "item ${item.id}: \"${item.title}\" -> \"${decided.title}\" " +
                        "[model: spoils=${verdict.spoils} \"${verdict.safeTitle}\"; ${decided.why}]",
                )
                SpoilerVerdict(item.id, decided.spoils, decided.title)
            }
        }
        return SpoilerAnswer(verdicts)
    }
}
