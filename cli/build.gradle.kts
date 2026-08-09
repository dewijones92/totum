// The desktop front end. Same libraries as the app, no Android anywhere near it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.dewijones92.totum.cli.MainKt")
    applicationName = "totum"
}

dependencies {
    implementation(project(":lib:common"))
    implementation(project(":lib:ytdlp"))
    implementation(project(":lib:ytdlp-process"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The engine fake that ships with the library, so the CLI's tests need no engine of their own.
    testImplementation(project(":lib:ytdlp"))
}

// The version travels in the jar manifest, so `totum version` reports the same number the
// release does without a generated source file to keep in step.
tasks.named<Jar>("jar") {
    manifest { attributes("Implementation-Version" to (project.findProperty("versionName") ?: "dev")) }
}

// A single archive to publish beside the APK. `installDist` gives the same tree locally.
tasks.named<Zip>("distZip") { archiveFileName.set("totum-cli.zip") }
tasks.named<Tar>("distTar") { archiveFileName.set("totum-cli.tar") }
