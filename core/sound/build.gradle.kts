plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":lib:common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
