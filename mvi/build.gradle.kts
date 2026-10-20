plugins {
    alias(libs.plugins.kortex.android.library)
    alias(libs.plugins.kortex.android.compose)
}

// Model-View-Intent plumbing shared by feature modules: the ViewModel base and the Compose hook
// that collects its one-shot effects. No feature code and no DI here.
android {
    namespace = "dev.kortex.mvi"
}

dependencies {
    // Screens subclass MviViewModel and collect its flows, so these are part of the API.
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.kotlinx.coroutines.android)
}
