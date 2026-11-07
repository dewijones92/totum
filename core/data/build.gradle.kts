plugins {
    alias(libs.plugins.kotlin.jvm)
    // The backup file is @Serializable; parsing elsewhere in this module only reads JSON.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:sound"))
    api(project(":lib:stats"))
    api(project(":lib:ytdlp"))
    api(project(":lib:innertube"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
  // The home-server client is HTTP-shaped; its credential handling is only meaningfully
  // testable by inspecting the headers that actually go out.
  testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
