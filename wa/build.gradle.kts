plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.wire)
}

android {
    namespace = "dev.kortex.wa"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

// Generate Kotlin from the vendored WAProto schema (src/main/proto/WAProto.proto).
wire {
    kotlin {}
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.curve25519)
    implementation(libs.signal.protocol)
    implementation(libs.wire.runtime)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}
