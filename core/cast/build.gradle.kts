plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":core:domain"))
    api(project(":lib:common"))

    testImplementation(libs.junit)
}
