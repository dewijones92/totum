plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":lib:reminders"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
