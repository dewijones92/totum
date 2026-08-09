pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Totum"
include(":app")
include(":core:data")
include(":core:database")
include(":core:domain")
include(":core:playback")
include(":lib:common")
include(":lib:innertube")
include(":lib:sabr")
include(":lib:ytdlp")
include(":lib:ytdlp-chaquopy")
include(":lib:ytdlp-process")
include(":cli")
