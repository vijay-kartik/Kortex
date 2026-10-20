import dev.kortex.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * A feature module (:finance, :links, :topics): Compose screens on :design and :mvi, Room storage,
 * Hilt wiring and the JVM unit-test basics. The module adds only what is specific to it.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("kortex.android.library")
            pluginManager.apply("kortex.android.compose")
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies {
                "implementation"(project(":design"))
                "implementation"(project(":mvi"))

                "implementation"(libs.findLibrary("androidx-core-ktx").get())
                "implementation"(libs.findLibrary("androidx-activity-compose").get())

                "implementation"(libs.findLibrary("room-runtime").get())
                "implementation"(libs.findLibrary("room-ktx").get())
                "ksp"(libs.findLibrary("room-compiler").get())

                "implementation"(libs.findLibrary("hilt-android").get())
                "ksp"(libs.findLibrary("hilt-compiler").get())
                "implementation"(libs.findLibrary("hilt-navigation-compose").get())

                "testImplementation"(libs.findLibrary("junit").get())
                "testImplementation"(libs.findLibrary("coroutines-test").get())
            }
        }
    }
}
