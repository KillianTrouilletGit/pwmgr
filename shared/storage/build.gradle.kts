plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
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
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
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
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter.api)
                implementation(libs.junit.jupiter.params)
                runtimeOnly(libs.junit.jupiter.engine)
            }
        }
    }
}

android {
    namespace = "com.pwmgr.storage"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
