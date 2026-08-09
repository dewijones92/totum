// Pure JVM on purpose: this is the platform-neutral engine API (types, port,
// fake). Only the real engine (:lib:ytdlp-chaquopy) needs Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":lib:common"))
    implementation(libs.kotlinx.coroutines.core)
    // The bridge contract — the JSON shape totum_ytdlp.py speaks — lives here because BOTH
    // engines speak it: the Android one over embedded CPython, the desktop one over a process.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
