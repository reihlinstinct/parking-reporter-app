pluginManagement {
    repositories {
        // KSP's marker and implementation are published on Maven Central.
        // Keep this group off Google and the Plugin Portal redirect path.
        // Both are official Central hosts. A runner edge can return a false404
        // from one; strict dependency verification still pins every artifact.
        exclusiveContent {
            forRepositories(
                mavenCentral(),
                maven { name = "KspCentralAlternate"; url = uri("https://repo1.maven.org/maven2") }
            )
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
            forRepositories(
                mavenCentral(),
                maven { name = "KspCentralAlternate"; url = uri("https://repo1.maven.org/maven2") }
            )
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
