import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Produce JVM 17 bytecode using whatever JDK is running Gradle (typically the user's JDK 21).
// We don't use jvmToolchain() — that asks Gradle to *find* a specific JDK install, and the user
// only has JDK 21 locally. Aligning Java + Kotlin compile targets is what matters for the
// "Inconsistent JVM-target compatibility" check.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("com.pwmgr.nativehost.MainKt")
    // Chrome/Edge native messaging invokes the executable with no args; the start script
    // produced by the `application` plugin handles classpath setup.
    applicationName = "pwmgr-native-host"
}
