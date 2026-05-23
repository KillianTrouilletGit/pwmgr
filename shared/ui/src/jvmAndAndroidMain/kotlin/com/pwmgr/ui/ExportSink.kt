package com.pwmgr.ui

/**
 * Platform-specific destination for an exported vault copy. The desktop binds this to
 * Swing's [javax.swing.JFileChooser]; Android (Phase 10b) will bind it to
 * `ActivityResultContracts.CreateDocument` via a launcher registered in MainActivity.
 *
 * The export is just the encrypted vault bytes verbatim — same format, same master password.
 * No transformation, no re-encryption, no separate "export format". The exported file is
 * indistinguishable from your live vault, which is the point: drop it back at
 * `%LOCALAPPDATA%\PwMgr\vault.enc` (or `filesDir/vault.enc` on Android) to "import".
 */
interface ExportSink {
    /**
     * Asks the user where to save [bytes] under the suggested name [suggestedFilename].
     *
     * Returns the chosen path / Uri as a display string on success, or null if the user
     * cancelled. Implementations swallow IOExceptions and return null — the caller surfaces
     * a generic "export failed" to the UI rather than leaking platform error text.
     */
    suspend fun saveBytes(suggestedFilename: String, bytes: ByteArray): String?
}
