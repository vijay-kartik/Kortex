plugins {
    alias(libs.plugins.kortex.android.feature)
}

// Links (the Links tab): a pure domain (models, ports, use cases), Room storage, page reading and
// on-device tag suggestions behind those ports, and MVI screens. :sync reads the Room types in
// data/ directly; everything else goes through the domain.
android {
    namespace = "dev.kortex.links"
}

dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.android)

    // Link tagging: page metadata scraping + on-device text embeddings
    implementation(libs.jsoup)
    implementation(libs.mediapipe.tasks.text)

    testImplementation(libs.json)
}
