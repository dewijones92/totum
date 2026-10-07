plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
