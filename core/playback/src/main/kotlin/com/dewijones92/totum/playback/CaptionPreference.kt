package com.dewijones92.totum.playback

public const val DEFAULT_CAPTION_LANGUAGE: String = "en"

public data class CaptionChoice(val on: Boolean = true, val language: String = DEFAULT_CAPTION_LANGUAGE) {
    val languageOrNull: String? get() = language.takeIf { on }
}

public interface CaptionStore {
    public fun choice(): CaptionChoice

    public fun save(choice: CaptionChoice)
}

public class InMemoryCaptionStore(private var held: CaptionChoice = CaptionChoice()) : CaptionStore {
    override fun choice(): CaptionChoice = held

    override fun save(choice: CaptionChoice) {
        held = choice
    }
}

internal class CaptionPreference(private val store: CaptionStore) {
    fun choose(chosen: String?) {
        val before = store.choice()
        store.save(if (chosen == null) before.copy(on = false) else CaptionChoice(on = true, language = chosen))
    }

    fun languageFor(): String? = store.choice().languageOrNull
}
