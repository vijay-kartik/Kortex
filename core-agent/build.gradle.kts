plugins {
    alias(libs.plugins.kortex.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.kortex.core"

    // Existing unit tests use JUnit 5 (Jupiter); run them on the JVM unit-test path.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.litert)

    testImplementation(libs.junit.jupiter)
    // Gradle 9 no longer puts the JUnit Platform launcher on the test runtime classpath itself.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)
}
