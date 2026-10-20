// Convention plugins shared by every module (kortex.android.*, kortex.jvm.library), so SDK levels,
// the JVM target and the common dependency sets live in one place.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
