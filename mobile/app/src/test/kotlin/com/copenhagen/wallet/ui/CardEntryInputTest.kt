package com.copenhagen.wallet.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.copenhagen.wallet.data.tokenizeBody
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class CardEntryInputTest {
    private val now = YearMonth.of(2026, 9)

    @Test fun numberShowsFirstEightAndFinalFourWhileMaskingOnlyPositionsNineThroughTwelve() {
        val raw = AnnotatedString("1234567890123456")
        assertEquals("1234 5678 •••• 3456", CardNumberTransformation.filter(raw).text.text)
        assertEquals("1234567890123456", raw.text)
    }

    @Test fun maskingStartsAtNinthDigitWithoutWaitingForTheWholeGroup() {
        val expected = mapOf(0 to "", 4 to "1234", 5 to "1234 5", 8 to "1234 5678",
            9 to "1234 5678 •", 12 to "1234 5678 ••••", 13 to "1234 5678 •••• 3",
            19 to "1234 5678 •••• 3456 789")
        expected.forEach { (length, display) ->
            assertEquals(display, CardNumberTransformation.filter(AnnotatedString("1234567890123456789".take(length))).text.text)
        }
    }

    @Test fun everyNumberCursorPositionRoundTripsAndAllSeparatorPositionsAreEditable() {
        (0..19).forEach { assertOffsets(CardNumberTransformation, "1234567890123456789".take(it)) }
        val mapping = CardNumberTransformation.filter(AnnotatedString("1234567890123456")).offsetMapping
        assertEquals(4, mapping.transformedToOriginal(4))
        assertEquals(4, mapping.transformedToOriginal(5))
        assertEquals(8, mapping.transformedToOriginal(9))
        assertEquals(8, mapping.transformedToOriginal(10))
        assertEquals(12, mapping.transformedToOriginal(14))
        assertEquals(19, mapping.originalToTransformed(16))
    }

    @Test fun expiryUsesOneFourDigitValueWithEditableMonthYearSeparator() {
        listOf("", "1", "12", "12 / 3", "12 / 30").forEachIndexed { length, expected ->
            val input = "1230".take(length)
            assertEquals(expected, CardExpiryTransformation.filter(AnnotatedString(input)).text.text)
            assertOffsets(CardExpiryTransformation, input)
        }
        val mapping = CardExpiryTransformation.filter(AnnotatedString("1230")).offsetMapping
        (2..5).forEach { assertEquals(2, mapping.transformedToOriginal(it)) }
    }

    @Test fun formattedPasteRetainsEveryDigitAndMapsSelectionAfterRemovingSeparators() {
        val pasted = "4111-1111 1111 1111"
        val result = cardDigits(TextFieldValue(pasted, TextRange(pasted.length)), 19)
        assertEquals("4111111111111111", result.text)
        assertEquals(TextRange(16), result.selection)
        val selected = cardDigits(TextFieldValue(pasted, TextRange(14, 5), TextRange(10, 14)), 19)
        assertEquals(TextRange(12, 4), selected.selection)
        assertEquals(TextRange(8, 12), selected.composition)
        assertEquals("1230", cardDigits(TextFieldValue("12 / 30"), 4).text)
    }

    @Test fun maximumIsAppliedToDigitsAndDoesNotMoveAnExistingMidNumberCursor() {
        val editing = TextFieldValue("4111111111111111", TextRange(8))
        assertSame(editing, cardDigits(editing, 19))
        val long = cardDigits(TextFieldValue("1234 5678 9012 3456 7890", TextRange(24)), 19)
        assertEquals("1234567890123456789", long.text)
        assertEquals(TextRange(19), long.selection)
        assertEquals("123", cardDigits(TextFieldValue("a1２2-3"), 4).text)
    }

    @Test fun fourDigitExpiryParsesMonthFirstAndProducesExistingApiFields() {
        val expiry = requireNotNull(cardExpiry("1299"))
        val body = tokenizeBody("4111111111111111", expiry.monthValue, expiry.year, "123")
        assertEquals(12, body.getValue("expiryMonth").jsonPrimitive.int)
        assertEquals(2099, body.getValue("expiryYear").jsonPrimitive.int)
        assertEquals("4111111111111111", body.getValue("cardNumber").jsonPrimitive.content)
        assertEquals(setOf("cardNumber", "expiryMonth", "expiryYear", "cvc"), body.keys)
    }

    @Test fun emptyIncompleteInvalidAndExpiredInputsGiveSpecificErrors() {
        val empty = cardEntryErrors("", "", "", now)
        assertFalse(empty.valid)
        assertEquals("카드 번호를 입력해 주세요.", empty.number)
        assertEquals("유효기간을 입력해 주세요.", empty.expiry)
        assertEquals("CVC를 3~4자리로 입력해 주세요.", empty.cvc)
        for (expiry in listOf("1", "123", "12300", "12/30", "１２３０")) assertNull(cardExpiry(expiry))
        for (expiry in listOf("0030", "1330")) {
            assertNull(cardExpiry(expiry))
            assertEquals("유효기간을 확인해 주세요.", cardEntryErrors("4111111111111111", expiry, "123", now).expiry)
        }
        assertEquals("유효기간이 지난 카드예요.", cardEntryErrors("4111111111111111", "0826", "123", now).expiry)
        assertNotNull(cardEntryErrors("4111111111111112", "1230", "123", now).number)
        assertNotNull(cardEntryErrors("4111111111111111", "1230", "12", now).cvc)
    }

    @Test fun correctedValuesCurrentMonthAndAllPreviouslySupportedCardLengthsRemainValid() {
        for (number in listOf("4111111111111111", "5555555555554444", "378282246310005", "4222222222222", "4000000000000000006")) {
            for (cvc in listOf("123", "1234")) assertTrue(cardEntryErrors(number, "0926", cvc, now).valid)
        }
        assertTrue(cardEntryErrors("4111111111111111", "1230", "123", now).valid)
    }

    private fun assertOffsets(transformation: VisualTransformation, value: String) {
        val transformed = transformation.filter(AnnotatedString(value))
        val map = transformed.offsetMapping
        val original = (0..value.length).map(map::originalToTransformed)
        val visual = (0..transformed.text.length).map(map::transformedToOriginal)
        assertEquals(original.sorted(), original)
        assertEquals(visual.sorted(), visual)
        original.forEach { assertTrue(it in 0..transformed.text.length) }
        visual.forEach { assertTrue(it in 0..value.length) }
        (0..value.length).forEach { assertEquals(it, map.transformedToOriginal(map.originalToTransformed(it))) }
    }
}
