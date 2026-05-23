package com.pwmgr.desktop.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Installs / uninstalls the native messaging host registration for Chromium-based browsers
 * (Chrome, Edge, Brave). The flow:
 *
 *  1. Write a Chrome-format native-host manifest JSON to `%LOCALAPPDATA%\PwMgr\com.pwmgr.host.json`
 *     declaring the path to our `pwmgr-native-host.bat` and the allowed extension origins.
 *  2. Add a registry value under `HKCU\Software\<vendor>\<product>\NativeMessagingHosts\com.pwmgr.host`
 *     pointing at that manifest. (Per-user, no admin required.)
 *
 * Brave and other Chromium forks read the same Chrome key, so installing once for Chrome
 * also covers them on most installs — but we still register Edge explicitly for users who
 * use Edge as their default browser.
 *
 * The native host .bat must exist at install time; built by `./gradlew :nativeHost:installDist`.
 */
class ExtensionInstaller(
    private val manifestPath: Path,
    private val nativeHostBatPath: Path,
) {

    enum class Browser(
        val displayName: String,
        val regVendor: String,
        val regProduct: String,
    ) {
        CHROME("Google Chrome", "Google", "Chrome"),
        EDGE("Microsoft Edge", "Microsoft", "Edge"),
    }

    data class InstallResult(
        val installedBrowsers: List<Browser>,
        val failures: Map<Browser, String>,
    ) {
        val ok: Boolean get() = installedBrowsers.isNotEmpty() && failures.isEmpty()
    }

    /**
     * Writes the native-host manifest and registers it under all supported browsers' registry
     * keys. Returns which browsers succeeded.
     *
     * @param extensionIds chrome-extension://<id>/ values to add to `allowed_origins`. The user
     *   gets these IDs from `chrome://extensions` after loading the unpacked extension.
     */
    fun install(extensionIds: List<String>): InstallResult {
        if (!Files.exists(nativeHostBatPath)) {
            return InstallResult(
                installedBrowsers = emptyList(),
                failures = Browser.entries.associateWith {
                    "Native host not built: run `gradlew :nativeHost:installDist` first " +
                        "(expected at $nativeHostBatPath)"
                },
            )
        }

        writeManifest(extensionIds)

        val installed = mutableListOf<Browser>()
        val failures = mutableMapOf<Browser, String>()
        for (browser in Browser.entries) {
            try {
                registerInRegistry(browser)
                installed += browser
            } catch (e: Exception) {
                failures[browser] = e.message ?: e::class.simpleName.orEmpty()
            }
        }
        return InstallResult(installed, failures)
    }

    fun uninstall(): InstallResult {
        val installed = mutableListOf<Browser>()
        val failures = mutableMapOf<Browser, String>()
        for (browser in Browser.entries) {
            try {
                unregisterFromRegistry(browser)
                installed += browser
            } catch (e: Exception) {
                failures[browser] = e.message ?: e::class.simpleName.orEmpty()
            }
        }
        runCatching { Files.deleteIfExists(manifestPath) }
        return InstallResult(installed, failures)
    }

    private fun writeManifest(extensionIds: List<String>) {
        val origins = extensionIds.map { id -> "chrome-extension://${id.trim('/')}/" }
        val manifest = NativeHostManifest(
            name = HOST_NAME,
            description = "PwMgr native messaging host — bridges the browser extension to the desktop app.",
            path = nativeHostBatPath.toAbsolutePath().toString(),
            type = "stdio",
            allowed_origins = origins,
        )
        Files.createDirectories(manifestPath.parent)
        val body = json.encodeToString(NativeHostManifest.serializer(), manifest)
        Files.newBufferedWriter(
            manifestPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { it.write(body) }
    }

    private fun registerInRegistry(browser: Browser) {
        val key = registryKey(browser)
        // `reg add KEY /ve /t REG_SZ /d VALUE /f` — `/ve` writes the default (unnamed) value,
        // which is what Chromium reads for the path-to-manifest.
        val args = listOf(
            "reg", "add", key,
            "/ve",
            "/t", "REG_SZ",
            "/d", manifestPath.toAbsolutePath().toString(),
            "/f",
        )
        runRegCommand(args, browser)
    }

    private fun unregisterFromRegistry(browser: Browser) {
        val key = registryKey(browser)
        val args = listOf("reg", "delete", key, "/f")
        runRegCommand(args, browser)
    }

    private fun registryKey(browser: Browser): String =
        """HKCU\Software\${browser.regVendor}\${browser.regProduct}\NativeMessagingHosts\$HOST_NAME"""

    private fun runRegCommand(args: List<String>, browser: Browser) {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw IOException("reg.exe exited with $exit for ${browser.displayName}: ${output.trim()}")
        }
    }

    @Serializable
    private data class NativeHostManifest(
        val name: String,
        val description: String,
        val path: String,
        val type: String,
        val allowed_origins: List<String>,
    )

    companion object {
        const val HOST_NAME = "com.pwmgr.host"
        private val json = Json { prettyPrint = true }
    }
}
