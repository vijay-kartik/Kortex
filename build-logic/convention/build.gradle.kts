plugins {
    `kotlin-dsl`
}

group = "dev.kortex.buildlogic"

dependencies {
    // compileOnly: the root build already puts these plugins on the classpath (`apply false`).
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "kortex.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "kortex.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "kortex.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "kortex.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "kortex.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
