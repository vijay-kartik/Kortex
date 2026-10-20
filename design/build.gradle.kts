plugins {
    alias(libs.plugins.kortex.android.library)
    alias(libs.plugins.kortex.android.compose)
}

// Kortex visual identity shared by :app and feature modules: palette, typography,
// motion curves, fonts and the common icon set.
android {
    namespace = "dev.kortex.design"
}

dependencies {
    // Theme tokens are Compose types (Color, FontFamily, Easing), so consumers get Compose.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    // BackHandler, for the search header collapsing on back.
    implementation(libs.androidx.activity.compose)
}
