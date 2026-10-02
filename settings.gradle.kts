pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only for Kadb's SPAKE2 dependency, which isn't published to Maven Central.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.Flyfish233") }
        }
    }
}

rootProject.name = "Thor-LED-Manager"
include(":app")
 