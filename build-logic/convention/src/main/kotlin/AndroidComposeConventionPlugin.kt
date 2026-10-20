import com.android.build.api.dsl.CommonExtension
import dev.kortex.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Compose on top of an Android module: the compiler plugin, the build feature and the BOM-pinned UI set. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<CommonExtension<*, *, *, *, *, *>>("android") {
                buildFeatures.compose = true
            }

            dependencies {
                "implementation"(platform(libs.findLibrary("compose-bom").get()))
                "implementation"(libs.findLibrary("compose-ui").get())
                "implementation"(libs.findLibrary("compose-material3").get())
                "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
                "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
            }
        }
    }
}
