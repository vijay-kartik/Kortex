plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Agent tools backed by the knowledge graph: the bridge between :core-agent's Tool API
// and :graph-storage, so neither of those modules has to depend on the other.
android {
    namespace = "dev.kortex.graph_tools"
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
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}

dependencies {
    api(project(":core-agent"))
    api(project(":knowledge-graph:graph-storage"))

    implementation(libs.kotlinx.serialization.json)
}
