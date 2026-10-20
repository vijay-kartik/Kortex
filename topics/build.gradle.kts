plugins {
    alias(libs.plugins.kortex.android.feature)
}

// Topics (My Info › Topics): domain, Room storage and MVI screens. Links, the agent summary and
// suggested topics come from the host through domain ports, so this module stays free of
// :links and :core-agent.
android {
    namespace = "dev.kortex.myinfo.topics"
}

dependencies {
    // In-app video playback through YouTube's official embed
    implementation(libs.youtube.player)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
