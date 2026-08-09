package com.dewijones92.totum.cli

/**
 * The version, injected at build time from the same `-PversionName` CI passes to the APK — so a
 * CLI and an APK from one run report the same number, and a bug report naming one names both.
 */
internal object BuildInfo {
    val VERSION: String = BuildInfo::class.java.`package`?.implementationVersion ?: "dev"
}
