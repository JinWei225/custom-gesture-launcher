package dev.neffly.gesturelauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two halves of [Calculator] are tested separately on purpose: that it computes the right
 * answer, and — at least as important for a search bar — that it stays quiet for the ordinary
 * queries it sees on every other keystroke.
 */
class CalculatorTest {

    private fun calc(query: String) = Calculator.evaluate(query)

    // --- arithmetic ---------------------------------------------------------

    @Test
    fun `evaluates the four operations with correct precedence`() {
        assertEquals("7", calc("1 + 2 * 3"))
        assertEquals("9", calc("(1 + 2) * 3"))
        assertEquals("2.5", calc("10 / 4"))
        assertEquals("-5", calc("5 - 10"))
    }

    /** The reason for choosing a BigDecimal evaluator over a double-based one. */
    @Test
    fun `adds decimals without binary floating point error`() {
        assertEquals("0.3", calc("0.1 + 0.2"))
    }

    @Test
    fun `supports powers, functions and constants`() {
        assertEquals("1,024", calc("2^10"))
        assertEquals("4", calc("sqrt(16)"))
        assertEquals("5", calc("abs(-5)"))
        assertEquals("3", calc("max(1, 3)"))
        assertEquals("3.1415926536", calc("pi * 1"))
    }

    /** Function names are matched case-insensitively, so nobody has to shout at a search box. */
    @Test
    fun `accepts functions in any case`() {
        assertEquals("4", calc("SQRT(16)"))
        assertEquals("4", calc("Sqrt(16)"))
    }

    // --- percentages --------------------------------------------------------

    @Test
    fun `reads percent of as a fraction of the second number`() {
        assertEquals("10", calc("20% of 50"))
        assertEquals("30", calc("15% of 200"))
    }

    @Test
    fun `adds and subtracts a percentage of the running total`() {
        assertEquals("55", calc("50 + 10%"))
        assertEquals("45", calc("50 - 10%"))
        // Anchored to the whole left-hand side: 10% of 3, not 10% of the 2 beside the sign.
        assertEquals("3.3", calc("1 + 2 + 10%"))
    }

    @Test
    fun `reads a bare percentage as a fraction`() {
        assertEquals("0.15", calc("15%"))
        assertEquals("30", calc("200 * 15%"))
    }

    // --- formatting ---------------------------------------------------------

    @Test
    fun `groups thousands and drops trailing zeros`() {
        assertEquals("1,000,000", calc("1000 * 1000"))
        assertEquals("2", calc("4 / 2"))
    }

    @Test
    fun `rounds a repeating decimal instead of printing full precision`() {
        assertEquals("0.3333333333", calc("1 / 3"))
    }

    @Test
    fun `falls back to exponent form for results too large to read`() {
        assertEquals("1E16", calc("10^16"))
    }

    // --- knowing when not to answer -----------------------------------------

    @Test
    fun `ignores ordinary search queries`() {
        assertNull(calc("gmail"))
        assertNull(calc("settings"))
        assertNull(calc("play store"))
    }

    /** The cases that made the undefined-identifier check worth delegating to the evaluator:
     *  every one of these contains both a digit and an operator character. */
    @Test
    fun `ignores words that merely look like arithmetic`() {
        assertNull(calc("e-mail"))
        assertNull(calc("3-in-1"))
        assertNull(calc("2x2"))
        assertNull(calc("wi-fi 6"))
    }

    @Test
    fun `ignores a bare number`() {
        assertNull(calc("12"))
        assertNull(calc("2024"))
    }

    @Test
    fun `ignores blank and malformed input`() {
        assertNull(calc(""))
        assertNull(calc("   "))
        assertNull(calc("1 +"))
        assertNull(calc("(1 + 2"))
    }

    /** Division by zero raises from inside BigDecimal rather than the library's own exceptions,
     *  which is why the catch is deliberately broad. */
    @Test
    fun `ignores division by zero`() {
        assertNull(calc("1 / 0"))
    }

    @Test
    fun `ignores text pasted in bulk`() {
        assertNull(calc("1 + 1 " + "x".repeat(300)))
    }
}
