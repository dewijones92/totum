plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dewijones92.totum.ytdlp.chaquopy"

    defaultConfig {
        // Travels with the module: Python calls ProgressListener.onProgress by name.
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

chaquopy {
    defaultConfig {
        // Must match the build machine's python3 minor version (Chaquopy requirement).
        version = "3.12"
        pip {
            install("yt-dlp")
            // The JS challenge solver scripts. yt-dlp finds a runtime through these — without
            // the package it reports "JS Challenge Providers: ... (unavailable)" and silently
            // returns a single 360p format for videos YouTube serves at 1080p. Shipping
            // QuickJS alone achieved nothing until this was added.
            install("yt-dlp-ejs")
            install("requests")
        }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":lib:ytdlp"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.javascriptengine)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
