plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.dewijones92.totum.playback"
}

androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin") }
        variant.deviceTests.values.forEach { it.sources.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin") }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":core:domain"))
    api(project(":lib:sabr"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.media3.exoplayer)
    // Without these, an HLS or DASH URL is not a degraded stream — it is a hard
    // ClassNotFoundException inside DefaultMediaSourceFactory and playback dies on the
    // spot. Found on the emulator 2026-07-31: a video resolved fine, "tracking acquired",
    // then "ended — nothing playing" a thousandth of a second later.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.session)
    // Cast: CastPlayer + CastContext, so playback can hand off to a Chromecast.
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)

    testImplementation(project(":core:data"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // ChunkedDataSource can only be driven with a real DataSpec, which needs an android.net.Uri.
    // No device behaviour and no network in these — see ChunkedDataSourceTest.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
