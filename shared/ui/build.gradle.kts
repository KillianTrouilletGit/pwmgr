plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
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
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
            }
        }
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
        }
        val jvmMain by getting {
            dependsOn(jvmAndAndroidMain)
        }
        val androidMain by getting {
            dependsOn(jvmAndAndroidMain)
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
