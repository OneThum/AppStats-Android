// Root build script for appstats-android.
// Copyright © 2026 One Thum Software

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "com.onethumsoftware"
    version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0-SNAPSHOT")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
