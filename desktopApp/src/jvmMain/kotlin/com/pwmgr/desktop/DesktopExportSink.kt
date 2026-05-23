package com.pwmgr.desktop

import com.pwmgr.ui.ExportSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing-based file save dialog. Runs the modal on the JVM AWT event thread (via
 * Dispatchers.IO is fine because JFileChooser internally hops to AWT).
 *
 * Defaults to the user's Downloads directory with the suggested timestamped filename.
 * Filters to `.enc` but lets the user override.
 */
class DesktopExportSink : ExportSink {

    override suspend fun saveBytes(suggestedFilename: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser(defaultDirectory()).apply {
            dialogTitle = "Export PwMgr vault"
            selectedFile = File(suggestedFilename)
            fileFilter = FileNameExtensionFilter("Encrypted vault (*.enc)", "enc")
        }
        val result = chooser.showSaveDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return@withContext null

        val target = chooser.selectedFile?.let { f ->
            if (f.extension.equals("enc", ignoreCase = true)) f else File(f.parentFile, f.name + ".enc")
        } ?: return@withContext null

        try {
            target.writeBytes(bytes)
            target.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun defaultDirectory(): File {
        val downloads = System.getProperty("user.home")?.let { File(it, "Downloads") }
        return if (downloads != null && downloads.isDirectory) downloads else File(System.getProperty("user.home"))
    }
}
