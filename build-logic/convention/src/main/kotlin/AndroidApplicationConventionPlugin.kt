import com.android.build.api.dsl.ApplicationExtension
import dev.kortex.buildlogic.TARGET_SDK
import dev.kortex.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** `:app`: the Android application plugin with the shared SDK levels and JVM target. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = TARGET_SDK
            }
        }
    }
}
