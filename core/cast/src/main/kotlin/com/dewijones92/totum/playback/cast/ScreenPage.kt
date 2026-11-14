package com.dewijones92.totum.playback.cast

public object ScreenPage {
    private const val RESOURCE = "/screen.html"

    public fun bundled(): ByteArray = requireNotNull(ScreenPage::class.java.getResourceAsStream(RESOURCE)) {
        "the screen page is missing from the classpath"
    }.use { it.readBytes() }
}
