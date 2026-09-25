plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Cloud sync: owns every Firebase dependency, so :links and :topics stay Firebase-free.
android {
    namespace = "dev.kortex.sync"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Same as :app: current Firebase artifacts carry newer Kotlin metadata than our compiler.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}

dependencies {
    // The local side of sync: dirty rows to push, and applying pulled ones.
    implementation(project(":links"))

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    // Records only: files stay on the device (no Cloud Storage), and item docs carry their path.
    implementation(libs.firebase.firestore)

    // Google sign-in through Credential Manager; the ID token is exchanged for a Firebase session.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Pull watermarks and "Last synced", per account.
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
}
