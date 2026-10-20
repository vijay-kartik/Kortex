import com.android.build.api.dsl.LibraryExtension
import dev.kortex.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Any Android library: the library and Kotlin plugins with the shared SDK levels and JVM target. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
            }
        }
    }
}
