pluginManagement {
    repositories {
        mavenCentral()
        // Same official Central content; handles a not-found response from one host.
        maven {
            name = "KotlinCentralAlternate"
            url = uri("https://repo1.maven.org/maven2")
            content { includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?") }
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            name = "KotlinCentralAlternate"
            url = uri("https://repo1.maven.org/maven2")
            content { includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?") }
        }
    }
}
rootProject.name = "MunicipalApiBridge"
