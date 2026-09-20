plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// compileSdk/minSdk, Java level, and lint policy come from the root build's androidDefaults.
android {
    namespace = "com.dewijones92.totum"
    defaultConfig {
        applicationId = "com.dewijones92.totum"
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Our own runner, only so POST_NOTIFICATIONS is granted before any test plays anything.
        // See TotumTestRunner for why it cannot be a shell script or a per-test rule.
        testInstrumentationRunner = "com.dewijones92.totum.TotumTestRunner"
        // CI passes monotonically increasing values (-PversionCode / -PversionName)
        // so Obtainium sees every main-tip build as an upgrade.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0-dev"
        // Short git SHA of the build, shown in-app so the running build is
        // unambiguous. CI passes -PgitSha; local builds resolve it or say "local".
        val gitSha = (project.findProperty("gitSha") as String?)
            ?: runCatching {
                ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(rootDir).start().inputStream.bufferedReader().readLine()?.trim()
            }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "local"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // The home server's domain, baked in so nothing has to be typed to use torrents.
        //
        // Injected rather than committed: the repo is public and the host is not something to
        // publish. Read from local.properties (`totum.homeServer=example.com`) or the
        // TOTUM_HOME_SERVER environment variable, which is how CI supplies it. Absent, it is
        // simply blank and the app falls back to asking — so a fork or a fresh clone builds
        // and runs perfectly well without knowing anything about it.
        val homeServer = (project.findProperty("totum.homeServer") as String?)
            ?: System.getenv("TOTUM_HOME_SERVER")
            ?: ""
        buildConfigField("String", "HOME_SERVER", "\"$homeServer\"")
    }

    signingConfigs {
        // Real key in CI (from secrets); local release builds fall back to the
        // debug key so they remain installable without the keystore.
        val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // Real devices only (Dewi's phone is arm64); debug keeps x86_64 for the
            // emulator. Pass -PemulatorAbis to smoke-test a release build on the emulator.
            ndk {
                abiFilters.clear()
                abiFilters += if (project.hasProperty("emulatorAbis")) {
                    listOf("arm64-v8a", "x86_64")
                } else {
                    listOf("arm64-v8a")
                }
            }
        }
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        // Extract native libs to nativeLibraryDir on install. Required so the
        // bundled ffmpeg (shipped as libffmpeg.so) lands as a real file in the
        // one app-private location that stays executable under Android 14 W^X;
        // yt-dlp execs it via ffmpeg_location.
        useLegacyPackaging = true
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  implementation(project(":core:data"))
  implementation(project(":core:database"))
  implementation(project(":core:playback"))
  implementation(project(":core:domain"))
  implementation(project(":lib:ytdlp-chaquopy"))
  implementation(project(":lib:innertube"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Video surface for the shared player (podcasts show artwork, videos render here)
  implementation(libs.media3.common)
  implementation(libs.media3.ui.compose)
  // Cast: the Cast button (MediaRouteButton) + CastContext options provider
  implementation(libs.play.services.cast.framework)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.mediarouter)
  // Background refresh for new-content notifications
  implementation(libs.androidx.work.runtime.ktx)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  implementation(libs.okhttp)

  // Image loading (thumbnails/artwork) — Coil over the OkHttp network layer
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // The fast player path is HTTP-shaped, so its tests drive a real InnerTubeClient against a
  // local server rather than faking the client and proving nothing about the parsing.
  testImplementation(libs.okhttp.mockwebserver)

  // Instrumented tests: jUnit rules and runners
  // The SABR playback test drives a real ExoPlayer; :core:playback keeps media3 internal.
  androidTestImplementation(libs.media3.exoplayer)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
