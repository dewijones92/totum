// Top-level build file where you can add configuration options common to all sub-projects/modules.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.kover) apply false
}

val detektFormatting = libs.detekt.formatting

// Ratchet upwards as coverage grows; never downwards without a recorded reason.
val MIN_LOGIC_MODULE_COVERAGE_PERCENT = 75

// One lint policy for every Android module, current and future.
val lintPolicy: Lint.() -> Unit = {
  warningsAsErrors = true
  abortOnError = true
  // Version-freshness nags break CI on every upstream release, not on code changes.
  // Dependency updates are handled deliberately, not by lint.
  disable += listOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
}

// One set of Android build defaults; modules declare only what is theirs
// (namespace, dependencies, features).
val projectCompileSdk = libs.versions.compileSdk.get().toInt()
val projectMinSdk = libs.versions.minSdk.get().toInt()

val androidDefaults: com.android.build.api.dsl.CommonExtension.() -> Unit = {
  compileSdk = projectCompileSdk
  defaultConfig.minSdk = projectMinSdk
  compileOptions.sourceCompatibility = JavaVersion.VERSION_17
  compileOptions.targetCompatibility = JavaVersion.VERSION_17
  lint.lintPolicy()
}

/**
 * Live tests — the ones that talk to real YouTube — are excluded from the ordinary `test` task and
 * run only when `totum.liveTests` is set.
 *
 * They belong in a separate phase for one reason and it is not speed: a GitHub runner is a
 * datacentre IP that YouTube bot-checks, so running them there would fail for the environment rather
 * than for the code. CI runs them through Dewi's home connection instead
 * (`tools/ci/live-test-via-home.sh`), where a failure means what it says.
 *
 * Marked by PACKAGE (`…video.live`) rather than by a name convention, so a new one cannot join the
 * default task by being called something slightly different.
 */
val liveTestsRequested = providers.gradleProperty("totum.liveTests").isPresent

// Every module gets the same static-analysis gate; adding a module adds its gate.
subprojects {
  apply(plugin = "io.gitlab.arturbosch.detekt")

  extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    source.setFrom(
      "src/main/java",
      "src/main/kotlin",
      "src/test/java",
      "src/test/kotlin",
      "src/androidTest/java",
      "src/androidTest/kotlin",
    )
    parallel = true
    autoCorrect = true
  }

  dependencies {
    add("detektPlugins", detektFormatting)
  }

  // Applied to every module's JVM test tasks, so a live test added anywhere is excluded by the
  // same rule rather than by remembering to configure its module.
  tasks.withType<Test>().configureEach {
    filter {
      if (liveTestsRequested) {
        includeTestsMatching("com.dewijones92.totum.*.live.*")
      } else {
        excludeTestsMatching("com.dewijones92.totum.*.live.*")
      }
      // BOTH branches. Most modules hold no live tests at all, so in the live phase their filter
      // matches nothing — and Gradle treats that as a failure. It killed `:core:domain:test` in CI
      // before `:app`'s live tests ran at all, so the one test the phase exists for never executed
      // and the red build blamed something else entirely.
      isFailOnNoMatchingTests = false
    }
    // Forwarded EXPLICITLY, because `-Dname=value` on the command line sets the property on Gradle's
    // own JVM and the fork that runs the tests never sees it -- so an investigation input looks like
    // it was ignored. An allowlist rather than a blanket copy: a test should be able to say which
    // inputs it takes.
    listOf("poToken", "poTokenBinding", "visitorData", "playerPoToken", "poTokenInUrl", "clientInfo", "sabrEndpoint", "ustreamerConfig", "sabrAudio").forEach { name ->
      providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
  }

  plugins.withId("com.android.application") {
    extensions.configure<ApplicationExtension> { androidDefaults() }
  }
  plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> { androidDefaults() }
  }

  // Coverage gate on logic modules; :app is report-only (Compose UI distorts numbers).
  // Adapter modules are exempt: they are thin bridges to on-device machinery (Room,
  // embedded Python, Media3) verified by instrumented tests, whose coverage the JVM
  // gate cannot see.
  val koverExemptAdapters = setOf(":core:database", ":lib:ytdlp-chaquopy", ":core:playback")
  if ((path.startsWith(":core") || path.startsWith(":lib")) && path !in koverExemptAdapters) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
      reports {
        verify {
          rule {
            minBound(MIN_LOGIC_MODULE_COVERAGE_PERCENT)
          }
        }
      }
    }
  }
}
