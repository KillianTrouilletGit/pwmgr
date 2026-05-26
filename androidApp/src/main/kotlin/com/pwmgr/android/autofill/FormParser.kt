package com.pwmgr.android.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/**
 * Walks an [AssistStructure] looking for login fields. We use, in priority order:
 *
 * 1. **Autofill hints** declared by the app or web framework (`android:autofillHints`,
 *    HTML `autocomplete=...`). These are the gold standard.
 * 2. **Input type flags** — anything with `TYPE_TEXT_VARIATION_PASSWORD` is a password
 *    field; `TYPE_TEXT_VARIATION_EMAIL_ADDRESS` is a strong username hint.
 * 3. **HTML attribute fallbacks** — `<input type="password">`, `name`/`id` containing
 *    "user", "email", "login".
 * 4. **Resource id / hint text** — Android views with id or hint containing "password",
 *    "user", "email".
 *
 * We pick the FIRST password candidate found and the username candidate closest to it in
 * tree order (typically just above it in a login form).
 */
object FormParser {

    fun parse(structure: AssistStructure, packageName: String): ParsedForm? {
        val candidates = mutableListOf<FieldCandidate>()
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            walk(root, candidates)
        }
        
        if (candidates.isEmpty()) return null

        val webDomain = candidates.firstNotNullOfOrNull { it.webDomain }
        val passwordIdx = candidates.indexOfFirst { it.kind == FieldKind.PASSWORD }
        val passwordField = candidates.getOrNull(passwordIdx)
        
        // Pick the username closest to the password field, or just the first username if no password field.
        val usernameField = if (passwordIdx != -1) {
            candidates.withIndex()
                .filter { it.value.kind == FieldKind.USERNAME }
                .minByOrNull { kotlin.math.abs(it.index - passwordIdx) }
                ?.value
        } else {
            candidates.firstOrNull { it.kind == FieldKind.USERNAME }
        }

        if (passwordField == null && usernameField == null) return null

        return ParsedForm(
            packageName = packageName,
            webDomain = webDomain?.normalizeHost(),
            usernameFieldId = usernameField?.id,
            passwordFieldId = passwordField?.id,
        )
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<FieldCandidate>) {
        classify(node)?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            walk(node.getChildAt(i), out)
        }
    }

    private fun classify(node: AssistStructure.ViewNode): FieldCandidate? {
        val autofillId = node.autofillId ?: return null
        val kind = detectKind(node) ?: return null
        val webDomain = node.webDomain?.takeIf { it.isNotBlank() }
        return FieldCandidate(autofillId, kind, webDomain)
    }

    private fun detectKind(node: AssistStructure.ViewNode): FieldKind? {
        // 1. Autofill hints (the standard).
        node.autofillHints?.forEach { hint ->
            when (hint?.lowercase()) {
                View.AUTOFILL_HINT_PASSWORD,
                "current-password",
                "new-password" -> return FieldKind.PASSWORD
                View.AUTOFILL_HINT_USERNAME,
                View.AUTOFILL_HINT_EMAIL_ADDRESS,
                "email",
                "username" -> return FieldKind.USERNAME
            }
        }

        // 2. Input-type flags.
        val inputType = node.inputType
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (klass == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> return FieldKind.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return FieldKind.USERNAME
            }
        }

        // 3. HTML <input type="password"> and similar.
        node.htmlInfo?.let { html ->
            val attrs = html.attributes?.associate { it.first.lowercase() to it.second.lowercase() } ?: emptyMap()
            val type = attrs["type"]
            if (type == "password") return FieldKind.PASSWORD
            val name = (attrs["name"] ?: "") + " " + (attrs["id"] ?: "") + " " + (attrs["autocomplete"] ?: "")
            if (name.containsAny("user", "email", "login")) return FieldKind.USERNAME
        }

        // 4. Resource id / hint text last-ditch heuristic.
        val haystack = listOfNotNull(
            node.idEntry,
            node.hint?.toString(),
            node.text?.toString(),
        ).joinToString(" ").lowercase()
        if (haystack.isNotEmpty()) {
            if (haystack.containsAny("password", "passwd", "mot de passe", "mdp", "senha", "contraseña")) return FieldKind.PASSWORD
            if (haystack.containsAny("username", "user_name", "user-name", "email", "login", "utilisateur", "correo")) return FieldKind.USERNAME
        }
        return null
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }

    /** Strip scheme/path/port to get just the host. */
    private fun String.normalizeHost(): String {
        var s = this.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        return s
    }
}

enum class FieldKind { USERNAME, PASSWORD }

private data class FieldCandidate(
    val id: AutofillId,
    val kind: FieldKind,
    val webDomain: String?,
)

/** Result of [FormParser.parse]. Null usernameFieldId means "this form is just a password prompt". */
data class ParsedForm(
    val packageName: String,
    val webDomain: String?,
    val usernameFieldId: AutofillId?,
    val passwordFieldId: AutofillId?,
)
