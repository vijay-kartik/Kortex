plugins {
    alias(libs.plugins.kortex.android.library)
    id("io.objectbox")
}

android {
    namespace = "com.samsung.android.graph_storage"
}

dependencies {
    api(project(":knowledge-graph:graph-core"))
}
