plugins { kotlin("jvm") version "2.4.21" }
kotlin { jvmToolchain(17) }

// A separate Gradle build: never configures :app, AGP, Compose, OCR or an SDK.
// Compile the exact production sources, not a copied municipal client.
val mainMunicipal = listOf("MunicipalClient", "MunicipalErrors", "MunicipalPayloads", "PythonJson", "ResponseDiagnostics", "StreetDirectory", "HouseNumber", "Itm")
val mainCore = listOf("MunicipalSubjects", "CityTopicCache", "ReportCategory")
kotlin.sourceSets.main {
    kotlin.srcDir("../app/src/main/java")
    kotlin.srcDir("../app/src/test/java")
    (mainMunicipal + listOf("BatchMunicipalBridge", "SupervisedApiTrialBridge", "TwoCarCiBridge")).forEach {
        kotlin.include("io/github/reihlinstinct/reporter/municipal/$it.kt")
    }
    mainCore.forEach { kotlin.include("io/github/reihlinstinct/reporter/core/$it.kt") }
}
kotlin.sourceSets.test {
    kotlin.srcDir("../app/src/test/java")
    listOf("BatchMunicipalBridgeTest", "SupervisedApiTrialBridgeTest", "TwoCarCiBridgeTest", "ItmTest", "MunicipalPayloadsTest", "PythonJsonTest").forEach {
        kotlin.include("io/github/reihlinstinct/reporter/municipal/$it.kt")
    }
}
sourceSets.test { resources.srcDir("../app/src/test/resources") }
sourceSets.main { java.srcDir("../scripts/jvm"); java.include("ApiOutgoingPhoto.java") }
sourceSets.test { java.srcDir("../scripts/jvm"); java.include("ApiOutgoingPhotoTest.java") }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.json:json:20260814")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
}
tasks.test { useJUnit(); systemProperty("java.awt.headless", "true"); maxHeapSize = "512m" }

// Keep the private runner's existing path; only the build producer changes.
tasks.register("writeBatchBridgeClasspath") {
    dependsOn(tasks.classes)
    doLast {
        val output = file("../app/build/batch-bridge/classpath.txt")
        output.parentFile.mkdirs()
        output.writeText(sourceSets.main.get().runtimeClasspath.asPath)
    }
}
