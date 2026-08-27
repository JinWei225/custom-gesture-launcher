package dev.neffly.gesturelauncher.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.ezylang.evalex.Expression
import com.ezylang.evalex.config.ExpressionConfiguration
import dev.neffly.gesturelauncher.R
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Turns a search query into a calculation, when it is one.
 *
 * Evaluation itself is [EvalEx][Expression]'s job — precedence, parentheses, the function library
 * and the constants all come from there, over BigDecimal so that `0.1 + 0.2` answers `0.3`. What
 * lives here is the two things a launcher needs on top of an evaluator: deciding whether a query is
 * a sum at all, and reading percentages the way people write them.
 *
 * **Deciding.** This runs on every keystroke against text that is usually an app name, so a false
 * positive is worse than a missed sum: a "CALCULATOR" row on top of a search for a contact would be
 * noise on every screen. Four things must hold, cheapest first — a digit, an operator, no
 * identifier the evaluator doesn't recognise, and a numeric result. The undefined-identifier check
 * is what does most of the work, and it is deliberately delegated rather than reimplemented as a
 * whitelist here: `getUndefinedVariables` knows the function and constant tables, so "e-mail" and
 * "3-in-1" are rejected for naming things that aren't maths, while `sqrt(16)` and `pi*2` are not.
 *
 * **Percentages.** `%` means percent, as it does in Raycast and Spotlight, not the modulo the
 * evaluator would otherwise make of it. Three shapes are rewritten into plain arithmetic before
 * parsing; see [expandPercentages].
 *
 * Not included: unit and currency conversion, and date arithmetic. Both need reference data this
 * app doesn't have and shouldn't fetch on a keystroke, so they are absent rather than approximated.
 */
object Calculator {

    /**
     * The formatted result of [query], or null when it isn't a calculation.
     *
     * Never throws: every failure mode — a syntax error, an unknown name, division by zero, a
     * result that isn't a number — means the same thing to the caller, which is "no calculator row".
     */
    fun evaluate(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        // A bare number is not a calculation — "12" is far more likely to be someone looking for an
        // app — so something has to be done to it. '(' counts, for `sqrt(16)` and friends.
        if (OPERATORS.none { it in trimmed }) return null
        if (trimmed.none { it.isDigit() }) return null

        return try {
            val expression = Expression(expandPercentages(trimmed), CONFIG)
            // Anything the evaluator can't name is a word, not a variable — this is the check that
            // keeps ordinary searches out of the calculator.
            if (expression.undefinedVariables.isNotEmpty()) return null
            val value = expression.evaluate()
            if (!value.isNumberValue) return null
            format(value.numberValue)
        } catch (e: Exception) {
            // Parse errors, unknown functions, division by zero and arithmetic overflow all land
            // here, and all mean "not a calculation" to the one caller. Deliberately broad: the
            // input is arbitrary text typed by a person, and the library's failure modes span both
            // its own exception types and plain java.lang.ArithmeticException.
            null
        }
    }

    /**
     * Puts [calculation]'s result on the clipboard and, where the system doesn't already say so,
     * confirms it.
     *
     * Copying is what the row is for — a sum has nothing to open — and it is what Raycast and
     * Spotlight do with Enter on the same row. From Android 13 the platform shows its own clipboard
     * confirmation, so a toast there would be the second thing saying the same thing at the same
     * moment; below it there is no such feedback and the copy would otherwise be silent.
     */
    fun copy(context: Context, calculation: SearchResult.Calculation) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(calculation.expression, calculation.result)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        Toast.makeText(
            context,
            context.getString(R.string.search_calculation_copied, calculation.result),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Rewrites the three ways people write a percentage into arithmetic the evaluator understands.
     *
     *  - `20% of 50`  -> `(20/100)*50`
     *  - `50 + 10%`   -> `(50)+((50)*10/100)`, and the same for `-`
     *  - `15%`        -> `(15/100)`
     *
     * The second is anchored to the end of the whole expression, which is both simpler and more
     * faithful than matching a pair of numbers anywhere: `1+2+10%` then adds 10% of `1+2`, the way
     * a person reading it would, rather than 10% of the `2` that happens to sit next to the sign.
     *
     * The cost of `%` meaning percent is that it no longer means modulo. That is the right trade
     * for a search bar — percentages of a number are asked for constantly and remainders almost
     * never — and it matches what the launchers this is modelled on do.
     */
    private fun expandPercentages(expression: String): String {
        var result = PERCENT_OF.replace(expression, "($1/100)*")
        result = PERCENT_ADJUST.replace(result) { match ->
            val (left, sign, percent) = match.destructured
            "($left)$sign(($left)*$percent/100)"
        }
        return PERCENT_BARE.replace(result, "($1/100)")
    }

    /**
     * A number as someone would write it down: grouped thousands, no trailing zeros, and the
     * separators of the reader's own locale.
     *
     * Rounding to [MAX_DECIMALS] is what stops `1/3` filling the row with the 34 digits the
     * evaluator's default precision produces. Beyond [SCIENTIFIC_ABOVE] it switches to exponent
     * form, because the alternative for something like `9^99` is a row of ninety-odd digits that
     * tells the reader nothing.
     */
    private fun format(value: BigDecimal): String {
        val pattern = if (value.abs() >= SCIENTIFIC_ABOVE) SCIENTIFIC_PATTERN else PLAIN_PATTERN
        val format = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.getDefault()))
        format.roundingMode = RoundingMode.HALF_UP
        return format.format(value)
    }

    /** Guards against pasting an essay into the search box: past this, it is not a sum. */
    private const val MAX_LENGTH = 200

    private const val PRECISION = 34
    private const val MAX_DECIMALS = 10

    private val OPERATORS = charArrayOf('+', '-', '*', '/', '^', '%', '(')

    private val SCIENTIFIC_ABOVE = BigDecimal("1E15")
    // Built from MAX_DECIMALS rather than written out, so the two can't drift apart.
    private val PLAIN_PATTERN = "#,##0." + "#".repeat(MAX_DECIMALS)
    private val SCIENTIFIC_PATTERN = "0." + "#".repeat(MAX_DECIMALS) + "E0"

    private val PERCENT_OF = Regex("""(\d+(?:\.\d+)?)\s*%\s*of\s+""", RegexOption.IGNORE_CASE)
    private val PERCENT_ADJUST = Regex("""^(.+)([+\-])\s*(\d+(?:\.\d+)?)\s*%$""")
    private val PERCENT_BARE = Regex("""(\d+(?:\.\d+)?)\s*%""")

    /** Declared last because Kotlin initialises an object's properties in source order, and this
     *  one reads [PRECISION]. Enough precision that rounding to [MAX_DECIMALS] is never the
     *  evaluator's own rounding showing through. */
    private val CONFIG: ExpressionConfiguration = ExpressionConfiguration.builder()
        .mathContext(MathContext(PRECISION, RoundingMode.HALF_UP))
        .build()
}
