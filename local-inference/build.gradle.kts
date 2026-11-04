import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
}

android {
    namespace = "inkpilot.inference"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            val requested = providers.gradleProperty("inkpilotAbi").orNull
            require(requested == null || requested in setOf("arm64-v8a", "x86_64"))
            abiFilters.addAll(requested?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":agent-core"))
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(libs.junit)
}
