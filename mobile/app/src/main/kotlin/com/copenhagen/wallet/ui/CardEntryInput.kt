package com.copenhagen.wallet.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.copenhagen.wallet.data.validCardNumber
import com.copenhagen.wallet.data.validCvc
import com.copenhagen.wallet.data.validExpiry
import java.time.YearMonth

/** Filter pasted separators before limiting length, preserving the user's selection. */
internal fun cardDigits(value: TextFieldValue, limit: Int): TextFieldValue {
    fun offset(position: Int) = value.text.take(position).count { it in '0'..'9' }.coerceAtMost(limit)
    val digits = value.text.filter { it in '0'..'9' }.take(limit)
    if (digits == value.text) return value
    return value.copy(text = digits,
        selection = TextRange(offset(value.selection.start), offset(value.selection.end)),
        composition = value.composition?.let { TextRange(offset(it.start), offset(it.end)) }?.takeUnless { it.collapsed })
}

internal object CardNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString) = groupedCardText(text.text, (4 until text.length step 4).toList(), " ") { index, digit ->
        if (index in 8..11) '•' else digit
    }
}

internal object CardExpiryTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString) = groupedCardText(text.text, if (text.length > 2) listOf(2) else emptyList(), " / ") { _, digit -> digit }
}

/** Separators are presentation only; every cursor position maps back to the same digits. */
private fun groupedCardText(value: String, breaks: List<Int>, separator: String, character: (Int, Char) -> Char): TransformedText {
    val display = buildString {
        value.forEachIndexed { index, digit ->
            if (index in breaks) append(separator)
            append(character(index, digit))
        }
    }
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = offset + breaks.count { it <= offset } * separator.length
        override fun transformedToOriginal(offset: Int): Int {
            var original = offset
            breaks.forEachIndexed { index, position ->
                val start = position + index * separator.length
                original -= (offset - start).coerceIn(0, separator.length)
            }
            return original.coerceIn(0, value.length)
        }
    }
    return TransformedText(AnnotatedString(display), mapping)
}

internal fun cardExpiry(value: String): YearMonth? {
    if (!value.matches(Regex("[0-9]{4}"))) return null
    val month = value.take(2).toInt()
    if (month !in 1..12) return null
    return YearMonth.of(2000 + value.takeLast(2).toInt(), month)
}

internal data class CardEntryErrors(val number: String? = null, val expiry: String? = null, val cvc: String? = null) {
    val valid get() = number == null && expiry == null && cvc == null
}

internal fun cardEntryErrors(number: String, expiry: String, cvc: String, now: YearMonth = YearMonth.now()): CardEntryErrors {
    val date = cardExpiry(expiry)
    return CardEntryErrors(
        number = when {
            number.isEmpty() -> "카드 번호를 입력해 주세요."
            !validCardNumber(number) -> "카드 번호를 확인해 주세요."
            else -> null
        },
        expiry = when {
            expiry.isEmpty() -> "유효기간을 입력해 주세요."
            expiry.length != 4 -> "유효기간을 확인해 주세요."
            date == null -> "유효기간을 확인해 주세요."
            !validExpiry(date.monthValue, date.year, now) -> "유효기간이 지난 카드예요."
            else -> null
        },
        cvc = if (validCvc(cvc)) null else "CVC를 3~4자리로 입력해 주세요."
    )
}
