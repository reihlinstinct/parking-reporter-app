import java.io.File

plugins {
    id("com.google.devtools.ksp")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.reihlinstinct.reporter"
    compileSdk = 37
    defaultConfig {
        // Installed identity stays stable across the source namespace rename.
        applicationId = "il.sidewalks.reporter"
        testApplicationId = "il.sidewalks.reporter.test"
        minSdk = 26
        targetSdk = 37
        if (providers.gradleProperty("previewArm64").isPresent) { ndk { abiFilters += "arm64-v8a" } }
        versionCode = 200
        versionName = "0.53.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
    sourceSets { getByName("test") { resources.srcDir("src/main/assets") } }
    buildTypes {
        debug {
            isTestCoverageEnabled = true
        }
    }
}

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
    }
    // Generated code is not production logic: R/BuildConfig, Room DAO implementations (*_Impl), Compose
    // ComposableSingletons and the KSP appfunctions registry. Hand-written code is never excluded (issue #44).
    val generatedFilters = listOf("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*_Impl.class", "**/*_Impl$*.class", "**/ComposableSingletons*.class", "**/appfunctions_aggregated_deps/**")
    // The Kotlin output folder differs between plugin versions; take every debug class folder that exists (issue #44: the report was empty).
    classDirectories.setFrom(fileTree(layout.buildDirectory) {
        include("tmp/kotlin-classes/debug/**/*.class", "intermediates/built_in_kotlinc/debug/**/*.class", "intermediates/javac/debug/**/*.class")
        exclude(generatedFilters)
    })
    sourceDirectories.setFrom("src/main/java", "src/main/kotlin")
    executionData.setFrom(fileTree(layout.buildDirectory) { include("outputs/unit_test_code_coverage/debugUnitTest/*.exec") })
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    // API36 stock indexers without dynamic schemas need the generated v1 asset too.
    arg("appfunctions:generateV1Xml", "true")
}
dependencies {
    implementation("androidx.appfunctions:appfunctions:1.0.0-rc01")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-rc01")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.ai.edge.litert:litert:2.3.2")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.google.crypto.tink:tink-android:1.24.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    kspAndroidTest("androidx.room:room-compiler:2.8.5")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    // Material3 no longer transitively supplies the navigation icon set.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Issue #297: compose ui-test idles through espresso, whose pre-3.7.0
    // releases reflect on InputManager.getInstance - removed in Android 17
    // (API 37). 3.7.0 uses getSystemService instead (androidx.test release
    // notes), unbreaking every compose test on the API 37 emulator.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Inactive batch bridge: compile once before secrets, then use private stdio IPC.
// This task does not run the bridge or make network calls.
tasks.register("writeBatchBridgeClasspath") {
    dependsOn("compileDebugUnitTestKotlin", "compileDebugJavaWithJavac", "compileApiOutgoingPhoto")
    doLast {
        val roots = listOf(
            layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile,
            layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest").get().asFile,
            layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/classes").get().asFile,
            layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/classes").get().asFile,
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes").get().asFile,
            layout.buildDirectory.dir("batch-bridge/photo-classes").get().asFile,
        )
        val deps = configurations.getByName("debugUnitTestRuntimeClasspath").files
        val output = layout.buildDirectory.file("batch-bridge/classpath.txt").get().asFile
        output.parentFile.mkdirs()
        output.writeText((roots.filter { it.exists() } + deps).joinToString(File.pathSeparator))
    }
}

// Desktop ImageIO belongs to the CI JVM, never Android's restricted Java bootclasspath.
// Never resolve Android's self-project unit runtime from a desktop Test task:
// AGP publishes multiple artifact variants, so use a separate JVM-only JUnit scope.
val apiOutgoingPhotoTestRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { add(apiOutgoingPhotoTestRuntime.name, "junit:junit:4.13.2") }
val compileApiOutgoingPhoto by tasks.registering(JavaCompile::class) {
    source(rootProject.fileTree("scripts/jvm") { include("ApiOutgoingPhoto*.java") })
    classpath = apiOutgoingPhotoTestRuntime
    destinationDirectory.set(layout.buildDirectory.dir("batch-bridge/photo-classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
val testApiOutgoingPhoto by tasks.registering(Test::class) {
    dependsOn(compileApiOutgoingPhoto)
    testClassesDirs = files(compileApiOutgoingPhoto.flatMap { it.destinationDirectory })
    classpath = files(testClassesDirs, apiOutgoingPhotoTestRuntime)
    useJUnit()
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "512m"
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(testApiOutgoingPhoto)
}

// Source-boundary unit tests must rerun for source-only edits, including androidTest.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main") { include("**/*.kt", "**/*.java") })
    inputs.files(fileTree("src/androidTest") { include("**/*.kt", "**/*.java") })
}
