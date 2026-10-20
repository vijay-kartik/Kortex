plugins {
    alias(libs.plugins.kortex.android.feature)
}

// Finances (docs/FINANCE_PLAN.md): domain, calculators, Room storage and MVI screens.
// Firebase-free like :links and :topics; finance sync lives in :sync.
android {
    namespace = "dev.kortex.finance"
}

dependencies {
    // Daily payment reminders
    implementation(libs.work.runtime.ktx)

    // Encrypted full numbers; reveal behind the screen lock
    implementation(libs.tink.android)
    implementation(libs.androidx.biometric)

    // Scan receipt
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)
}
