rootProject.name = "PasswordManagerMultiplatform"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

include(":shared:crypto")
include(":shared:core")
include(":shared:storage")
include(":shared:ui")
include(":desktopApp")
include(":androidApp")
