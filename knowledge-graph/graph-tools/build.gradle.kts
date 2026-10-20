plugins {
    alias(libs.plugins.kortex.android.library)
}

// Agent tools backed by the knowledge graph: the bridge between :core-agent's Tool API
// and :graph-storage, so neither of those modules has to depend on the other.
android {
    namespace = "dev.kortex.graph_tools"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    api(project(":core-agent"))
    api(project(":knowledge-graph:graph-storage"))

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
