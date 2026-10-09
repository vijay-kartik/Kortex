import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("com.google.gms.google-services")
}

// Read the OpenAI key from local.properties (gitignored) so secrets never enter VCS.
// Add a line `OPENAI_API_KEY=sk-...` to local.properties before building.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.kortex.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kortex.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            "\"${localProps.getProperty("OPENAI_API_KEY", "")}\"",
        )
        // Firebase Auth's *web* client id, which Credential Manager wants as its
        // serverClientId. Copy it from Authentication > Google > Web SDK configuration.
        buildConfigField(
            "String",
            "FIREBASE_WEB_CLIENT_ID",
            "\"${localProps.getProperty("FIREBASE_WEB_CLIENT_ID", "")}\"",
        )
        // Vercel AI Gateway key (https://vercel.com/<team>/~/ai-gateway › API keys).
        buildConfigField(
            "String",
            "AI_GATEWAY_API_KEY",
            "\"${localProps.getProperty("AI_GATEWAY_API_KEY", "")}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // MediaPipe memory-maps .tflite models straight from the APK, which requires them uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { 
        jvmTarget = "17" 
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Lets JVM tests hand a plain `Intent()` through, as the Gmail consent flow does.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":knowledge-graph:graph-core"))
    implementation(project(":knowledge-graph:graph-storage"))
    implementation(project(":knowledge-graph:graph-tools"))
    implementation(project(":links"))
    implementation(project(":topics"))
    implementation(project(":finance"))
    implementation(project(":design"))
    implementation(project(":sync"))
    implementation(libs.ktor.client.okhttp)

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    // App Check: `financeKey` only answers this app, attested by Play Integrity (debug builds use the debug provider).
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // App lock: BiometricPrompt needs a FragmentActivity; fragment pinned past biometric's old transitive one.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Background push of local changes when the app isn't on screen (docs/SMS_AUTO_PLAN.md, phase 7).
    implementation(libs.work.runtime.ktx)

    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.material3)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
}
