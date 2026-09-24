plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Kortex visual identity shared by :app and feature modules: palette, typography,
// motion curves, fonts and the common icon set.
android {
    namespace = "dev.kortex.design"
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
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Theme tokens are Compose types (Color, FontFamily, Easing), so consumers get Compose.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    // BackHandler, for the search header collapsing on back.
    implementation(libs.androidx.activity.compose)
}
