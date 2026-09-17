pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kortex"
include(":core-agent")
include(":app")
include(":knowledge-graph:graph-core")
include(":knowledge-graph:graph-storage")
include(":links")
