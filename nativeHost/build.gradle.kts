import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("com.pwmgr.nativehost.MainKt")
    // Produced installation has start-script invocations like `nativeHost --some-arg`. The
    // Chrome/Edge native messaging contract just runs the executable directly — no args.
    applicationName = "pwmgr-native-host"
}
