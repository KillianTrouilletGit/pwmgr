# Build instructions

## Prerequisites

| Tool | Required version | Why | Suggested install (Windows) |
|---|---|---|---|
| JDK | **17 or 21 (LTS)** | Kotlin Multiplatform + Compose Multiplatform 1.7 require JDK 17+. JDK 21 is the current LTS and Gradle 8.5+ targets it natively. | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Android SDK | **API 34**, build-tools 34.0.0, platform-tools | For `:androidApp` (compile and install). minSdk is 26. | Android Studio Koala+ (installs SDK automatically). |
| Git | any | Source control. | `winget install Git.Git` |

After installing the Android SDK, create `local.properties` at the project root pointing at it (gitignored):

```
sdk.dir=C:\\Users\\YOURUSER\\AppData\\Local\\Android\\Sdk
```

You don't need to install Gradle yourself — the project uses the Gradle wrapper. The first run of `gradlew.bat` downloads the correct Gradle version into `~/.gradle/wrapper`.

## One-time setup

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
# Close and reopen your shell so JAVA_HOME picks up.
java -version    # should print "21.0.x"
```

Set `JAVA_HOME` if not already set:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot", "User")
```

(Adjust path to match the installed version. The Temurin installer offers a checkbox to set `JAVA_HOME` automatically — use that.)

## First build

The wrapper jar is not committed by default (Phase 1 doesn't ship it). Bootstrap it once:

```powershell
# Option A: install Gradle once, generate the wrapper, then uninstall Gradle if you like.
winget install Gradle.Gradle
gradle wrapper --gradle-version 8.10

# Option B: download the wrapper jar directly.
$wrapperUrl = "https://raw.githubusercontent.com/gradle/gradle/v8.10.0/gradle/wrapper/gradle-wrapper.jar"
Invoke-WebRequest -Uri $wrapperUrl -OutFile gradle\wrapper\gradle-wrapper.jar
```

After that, all commands use the wrapper:

```powershell
.\gradlew.bat :shared:crypto:test
.\gradlew.bat :shared:core:test
```

## Running tests

```powershell
.\gradlew.bat :shared:crypto:jvmTest :shared:core:jvmTest
```

This compiles the JVM target and runs the unit tests for the crypto round-trip and tamper-detection suites. The crypto and core modules also expose an `androidTarget`; the test suite currently runs only on JVM (functionally equivalent — both targets use the same `jvmAndAndroidMain` source set with BouncyCastle and JCA).

## Running the Android app

```powershell
.\gradlew.bat :androidApp:installDebug
```

This requires either a connected device with USB debugging enabled, or a running emulator. The first build downloads Compose, AGP, and androidx artifacts (~200 MB).

To run the app without installing manually, `adb shell am start -n com.pwmgr.android/.MainActivity` after the install.

## Module layout

See [README.md](../README.md) and the [main plan](../README.md#modules) for module responsibilities.
