plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.wire)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.kortex.wa"
    // Match the consuming app: an SDK compiled against an older platform than its consumers
    // cannot see newer APIs and triggers manifest/lint mismatches on their side.
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

// Generate Kotlin from the vendored WAProto schema (src/main/proto/WAProto.proto).
wire {
    kotlin {}
}

dependencies {
    // `api`, not `implementation`: these types appear in the module's own public API, so they
    // must be on a consumer's compile classpath. WhatsAppManager exposes StateFlow<State> and
    // takes a suspend callback; without this a consumer cannot even reference them.
    api(libs.kotlinx.coroutines.core)

    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.curve25519)
    implementation(libs.signal.protocol)
    implementation(libs.wire.runtime)

    // Session/persistence (WhatsAppManager, credential + Signal-store persistence)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Login/observing UI (WhatsAppScreen). Compose is `api` because WhatsAppScreen is a
    // @Composable a consumer calls from their own composition.
    // TODO(sdk): split into an optional :wa-ui artifact so headless consumers are not forced
    //  to depend on Compose at all.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.zxing.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}
