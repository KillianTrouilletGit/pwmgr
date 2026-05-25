import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":shared:core"))
                api(project(":shared:crypto"))
                api(project(":shared:storage"))
                // kotlinx-datetime.Clock leaks through AppState's public constructor default.
                api(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // Compose deps are api so downstream apps (notably :androidApp's autofill
                // activity) can write Compose UIs without redeclaring every artifact.
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.materialIconsExtended)
                api(compose.ui)
            }
        }
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                // Pure-Java password strength estimator (Java 8+). Shared across JVM + Android.
                implementation(libs.zxcvbn)
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                // JNA + JNA-Platform expose Crypt32 (DPAPI) on Windows for biometric/convenience unlock.
                implementation(libs.jna)
                implementation(libs.jna.platform)
            }
        }
        val androidMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                // BiometricPrompt + CryptoObject for Android biometric unlock.
                implementation(libs.androidx.biometric)
            }
        }
    }
}

android {
    namespace = "com.pwmgr.ui"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
