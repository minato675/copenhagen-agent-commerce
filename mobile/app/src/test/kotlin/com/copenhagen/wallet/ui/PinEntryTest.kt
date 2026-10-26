package com.copenhagen.wallet.ui

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class PinEntryTest {
    @Test fun keypadHasEveryDigitExactlyOnce() {
        repeat(100) {
            val digits = shuffledPinDigits()
            assertEquals(10, digits.size)
            assertEquals(('0'..'9').toList(), digits.sorted())
        }
    }

    @Test fun shuffleUsesBoundedSecureRandomForEveryPosition() {
        val bounds = mutableListOf<Int>()
        val random = object : SecureRandom() {
            override fun nextInt(bound: Int): Int {
                bounds += bound
                return 0
            }
        }
        val digits = shuffledPinDigits(random)
        assertEquals((10 downTo 2).toList(), bounds)
        assertNotEquals(('0'..'9').toList(), digits)
        assertEquals(('0'..'9').toList(), digits.sorted())
    }

    @Test fun keypadAcceptsOnlySixAsciiDigitsAndPreservesLeadingZero() {
        val value = "024680".fold("") { pin, digit -> appendPinDigit(pin, digit) }
        assertEquals("024680", value)
        assertEquals(value, appendPinDigit(value, '9'))
        assertEquals("", appendPinDigit("", 'a'))
        assertEquals("", appendPinDigit("", '\u0661'))
        assertEquals("bad", appendPinDigit("bad", '1'))
    }
}
