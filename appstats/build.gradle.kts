// Library module: com.onethumsoftware:appstats-android
// Copyright © 2026 One Thum Software

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.onethumsoftware.appstats"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs =
            freeCompilerArgs +
            listOf(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
    }

    buildFeatures {
        buildConfig = true
    }

    // Apply Kotlin's explicit-API mode only to production sources; tests intentionally
    // use shorthand declarations.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        val isTest =
            name.contains("UnitTest", ignoreCase = true) ||
                name.contains("AndroidTest", ignoreCase = true)
        if (!isTest) {
            compilerOptions.freeCompilerArgs.add("-Xexplicit-api=warning")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // The Vanniktech maven-publish plugin configures singleVariant("release")
    // with sources + javadoc JARs automatically — no `publishing { ... }` block needed.
}

dependencies {
    api(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

// Publishing is configured entirely via gradle.properties:
//   - SONATYPE_HOST=CENTRAL_PORTAL                publishes to Maven Central Portal
//   - RELEASE_SIGNING_ENABLED=true                signs releases (requires GPG secrets)
//   - GROUP, POM_ARTIFACT_ID, VERSION_NAME        coordinates
//   - POM_NAME / POM_DESCRIPTION / POM_URL / etc. POM metadata
// The Vanniktech plugin reads all of these automatically.

ktlint {
    version.set("1.3.1")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    autoCorrect = false
}
