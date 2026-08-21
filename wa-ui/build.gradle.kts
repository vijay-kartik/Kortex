plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

android {
    namespace = "dev.kortex.wa.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // `api`: WhatsAppManager appears in WhatsAppScreen's own signature, so consumers of the UI
    // artifact need the core one on their compile classpath anyway.
    api(project(":wa"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // QR rendering for the pairing screen.
    implementation(libs.zxing.core)
}

// Wrapped in afterEvaluate because the Android `release` software component only exists once
// AGP has finished configuring its variants.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "space.pitchstone"
                artifactId = "tether-ui"
                version = providers.gradleProperty("tether.version").get()

                pom {
                    name.set("Tether UI")
                    description.set("Optional Compose surface for Tether: QR pairing, connection status and an observed-message list.")
                    url.set("https://github.com/vijay-kartik/Kortex")
                    // TODO(license): the repo has no LICENSE file yet, so this declaration is
                    //  not yet backed by anything. Add one (or change this) before publishing -
                    //  a POM that names a licence the source does not carry is worse than useless
                    //  to a consumer, and an artifact with no licence at all reads as
                    //  all-rights-reserved.
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("vijay-kartik")
                            name.set("Kartik Vijayvergiya")
                        }
                    }
                    scm {
                        url.set("https://github.com/vijay-kartik/Kortex")
                        connection.set("scm:git:https://github.com/vijay-kartik/Kortex.git")
                        developerConnection.set("scm:git:ssh://git@github.com/vijay-kartik/Kortex.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/vijay-kartik/Kortex")
                credentials {
                    username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                    password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
