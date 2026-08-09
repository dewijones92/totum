// Pure JVM: the desktop half of the engine. The Android half (:lib:ytdlp-chaquopy) embeds
// CPython; this one runs the SAME bridge script as a subprocess against the system python.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

/**
 * The bridge script, copied rather than duplicated.
 *
 * One file on disk, in the Chaquopy module where Android needs it, packaged here as a resource
 * so the desktop engine extracts exactly what the phone runs. A hand-kept second copy would
 * drift within a week — see the DRY law in CLAUDE.md.
 */
val bridgeScript by tasks.registering(Copy::class) {
    from(rootProject.file("lib/ytdlp-chaquopy/src/main/python/totum_ytdlp.py"))
    into(layout.buildDirectory.dir("generated/bridge"))
}

sourceSets.named("main") { resources.srcDir(bridgeScript) }

dependencies {
    api(project(":lib:ytdlp"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
