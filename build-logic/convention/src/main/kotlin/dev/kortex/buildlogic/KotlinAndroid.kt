package dev.kortex.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// The one place the SDK levels and the JVM target are set. Raise them here, not in a module.
internal const val COMPILE_SDK = 36
internal const val TARGET_SDK = 36
internal const val MIN_SDK = 26
private val JAVA_VERSION = JavaVersion.VERSION_17
private val JVM_TARGET = JvmTarget.fromTarget(JAVA_VERSION.toString())

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** SDK levels, test runner and JVM target shared by the app and every Android library. */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = COMPILE_SDK

        defaultConfig {
            minSdk = MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JVM_TARGET)
        }
    }
}

/** The same JVM target for pure Kotlin/JVM modules, which Android modules consume. */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JAVA_VERSION
        targetCompatibility = JAVA_VERSION
    }

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JVM_TARGET)
        }
    }
}
