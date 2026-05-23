plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pwmgr.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pwmgr.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Release signing — config read from gradle.properties or env vars (see docs/RELEASE.md).
    // If credentials are absent we skip the signingConfig so debug builds still work.
    val keystorePath = (findProperty("pwmgr.android.keystore") as String?) ?: System.getenv("PWMGR_KEYSTORE")
    val keystorePassword = (findProperty("pwmgr.android.keystorePassword") as String?) ?: System.getenv("PWMGR_KEYSTORE_PASSWORD")
    val keyAlias = (findProperty("pwmgr.android.keyAlias") as String?) ?: System.getenv("PWMGR_KEY_ALIAS")
    val keyPassword = (findProperty("pwmgr.android.keyPassword") as String?) ?: System.getenv("PWMGR_KEY_PASSWORD")

    if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Without R8 + resource shrinking the release APK pulls in the full
            // material-icons-extended set (~30 MB of unused icons). Compose handles its
            // own keep rules via the bundled `compose-rules.pro`; we layer our own on top.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":shared:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.fragment.ktx)

    // kotlin("test-junit5") brings in kotlin.test assertions + JUnit 5 runner.
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
