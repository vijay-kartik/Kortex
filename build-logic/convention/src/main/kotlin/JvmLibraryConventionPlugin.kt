import dev.kortex.buildlogic.configureKotlinJvm
import org.gradle.api.Plugin
import org.gradle.api.Project

/** A pure Kotlin/JVM library (:knowledge-graph:graph-core), on the same JVM target as the Android modules. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            configureKotlinJvm()
        }
    }
}
