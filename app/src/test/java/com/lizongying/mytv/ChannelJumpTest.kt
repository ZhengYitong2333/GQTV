package com.lizongying.mytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules behind the in-browser number shortcut. The fragment only owns the
 * overlay; everything that decides when a number commits lives here.
 */
class ChannelJumpTest {

    /** Channels 1..67 exist; everything else is missing. */
    private fun catalog() = ChannelJump { number -> (number - 1).takeIf { number in 1..67 } }

    @Test
    fun singleDigitWaitsForTheSecond() {
        val jump = catalog()
        assertFalse(jump.type('1'))
        assertEquals("1", jump.typed)
        assertTrue(jump.type('3'))
        assertEquals("13", jump.typed)
    }

    @Test
    fun commitResolvesTheBufferedNumberAndClearsIt() {
        val jump = catalog()
        jump.type('1')
        jump.type('3')
        assertEquals(12, jump.commit())
        assertEquals("", jump.typed)
        assertEquals(12, jump.lastJump)
    }

    @Test
    fun commitWithAnEmptyBufferDoesNothing() {
        val jump = catalog()
        assertNull(jump.commit())
        assertNull(jump.lastJump)
    }

    @Test
    fun missingChannelClearsTheBufferWithoutJumping() {
        val jump = catalog()
        jump.type('9')
        jump.type('9')
        assertNull(jump.commit())
        assertEquals("", jump.typed)
        assertNull(jump.lastJump)
    }

    @Test
    fun zeroIsNotAChannelAndLeavesNoStaleJump() {
        val jump = catalog()
        jump.type('1')
        jump.type('0')
        assertEquals(9, jump.commit())
        jump.type('0')
        assertNull(jump.commit())
        assertEquals(9, jump.lastJump)
    }

    @Test
    fun twoDigitsKeepOnlyTheLastTwo() {
        // The overlay commits at two digits, but a third press before the posted
        // commit lands must not turn "123" into a bogus number.
        val jump = ChannelJump { number -> number.takeIf { it == 12 } }
        jump.type('1')
        jump.type('2')
        jump.type('3')
        assertEquals("123", jump.typed)
        assertNull(jump.commit())
    }

    @Test
    fun resetDropsTheBufferAndTheLastJump() {
        val jump = catalog()
        jump.type('1')
        jump.type('3')
        jump.commit()
        jump.reset()
        assertEquals("", jump.typed)
        assertNull(jump.lastJump)
    }

    @Test
    fun leadingDigitIsResolvedAsOneBasedChannel() {
        val jump = catalog()
        jump.type('0')
        jump.type('1')
        assertEquals(0, jump.commit())
    }
}
