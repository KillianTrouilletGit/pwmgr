import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared:ui"))
                implementation(compose.desktop.currentOs)
                // @Serializable / Json used by HandshakeFile + ExtensionInstaller + DesktopOAuth.
                implementation(libs.kotlinx.serialization.json)
                implementation("com.formdev:flatlaf:3.4")
                implementation(libs.jna)
                implementation(libs.jna.platform)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.pwmgr.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "PwMgr"
            packageVersion = "0.1.0"
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// The browser-extension installer in the desktop app looks for the built native-host
// distribution at ../nativeHost/build/install/pwmgr-native-host/. Make sure it exists
// whenever the user runs the desktop app.
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(":nativeHost:installDist")
}
