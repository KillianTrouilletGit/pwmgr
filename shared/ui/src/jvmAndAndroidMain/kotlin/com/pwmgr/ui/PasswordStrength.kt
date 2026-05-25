package com.pwmgr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nulabinc.zxcvbn.Zxcvbn

/**
 * 5-level zxcvbn strength estimator. Wraps Nulab's pure-Java port of Dropbox's reference
 * implementation. Same library works unchanged on JVM and Android.
 *
 * Scoring is 0..4. We map to a label + color and surface as a horizontal bar with five
 * segments lit progressively.
 *
 * Cheap enough to call on every keystroke — typical eval is <1 ms for typical inputs.
 * Lazy-init the [Zxcvbn] instance because its dictionary load takes ~150 ms.
 */
object PasswordStrength {

    /** Reusable evaluator. Created lazily on first call to avoid paying startup cost up front. */
    private val zxcvbn: Zxcvbn by lazy { Zxcvbn() }

    /** Minimum score we accept for a master password. Below this, the create/recovery flow blocks. */
    const val MIN_ACCEPTABLE_SCORE: Int = 2

    /**
     * Score on a 0..4 scale.
     *  0 = "too guessable" (cracked in <10² guesses)
     *  1 = "very guessable" (<10⁶)
     *  2 = "somewhat guessable" (<10⁸) — our floor
     *  3 = "safely unguessable" (<10¹⁰)
     *  4 = "very unguessable" (≥10¹⁰)
     */
    fun score(password: String): Int = if (password.isEmpty()) 0 else zxcvbn.measure(password).score
}

@Composable
fun PasswordStrengthBar(password: String, modifier: Modifier = Modifier) {
    val score = remember(password) { PasswordStrength.score(password) }
    val (label, accent) = labelAndColor(score)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Five segments lit progressively up to (score + 1). Empty password = 0 lit.
            val lit = if (password.isEmpty()) 0 else score + 1
            repeat(5) { idx ->
                val color = if (idx < lit) accent else MaterialTheme.colorScheme.surfaceVariant
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(color, RoundedCornerShape(3.dp)),
                )
            }
        }
        if (password.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun labelAndColor(score: Int): Pair<String, Color> = when (score) {
    0 -> "Too weak — easily guessed" to Color(0xFFD32F2F)
    1 -> "Weak — try harder" to Color(0xFFF57C00)
    2 -> "OK — minimum acceptable" to Color(0xFFFBC02D)
    3 -> "Strong" to Color(0xFF388E3C)
    else -> "Very strong" to Color(0xFF1B5E20)
}
