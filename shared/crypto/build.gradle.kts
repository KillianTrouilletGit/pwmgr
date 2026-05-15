plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // Shared between JVM (Compose Desktop) and Android — both have javax.crypto
        // and run BouncyCastle identically, so actuals live here once.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.bouncycastle.bcprov)
            }
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
    namespace = "com.pwmgr.crypto"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
