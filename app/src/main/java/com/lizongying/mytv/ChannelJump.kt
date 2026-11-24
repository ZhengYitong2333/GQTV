package com.lizongying.mytv

/**
 * Typing buffer behind the number shortcut.
 *
 * Kept out of [MainFragment] so the commit rules can be unit-tested: two digits
 * commit at once, one digit waits for the timeout, and a failed lookup clears
 * what was typed instead of retrying it against the next press.
 */
internal class ChannelJump(private val resolve: (Int) -> Int?) {

    companion object { const val SECOND_DIGIT_TIMEOUT_MS = 1_200L }

    var typed: String = ""
        private set

    /** The channel number still buffered, or null when nothing usable is pending. */
    val pending: Int? get() = typed.toIntOrNull()

    /** Result of the last [commit], set only when a number was actually resolved. */
    var lastJump: Int? = null
        private set

    /** True when [type] completed a number, so the caller can skip its commit timer. */
    fun type(digit: Char): Boolean {
        typed += digit
        return typed.length >= 2
    }

    fun clear() {
        typed = ""
    }

    /**
     * Resolves the buffered digits. Returns the target position, or null when the
     * buffer was empty or named a channel that does not exist. Either way the
     * buffer is consumed.
     */
    fun commit(): Int? {
        val number = pending ?: return null
        typed = ""
        if (number <= 0) return null
        val position = resolve(number) ?: return null
        lastJump = position
        return position
    }

    fun reset() {
        typed = ""
        lastJump = null
    }
}
