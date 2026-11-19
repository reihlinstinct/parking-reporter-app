pluginManagement {
    repositories {
        // KSP's marker and implementation are published on Maven Central.
        // Keep this group off Google and the Plugin Portal redirect path.
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}
rootProject.name = "ParkingReporter"
include(":app")
