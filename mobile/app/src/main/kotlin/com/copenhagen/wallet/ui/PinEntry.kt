package com.copenhagen.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.security.SecureRandom

private const val PinLength = 6

internal fun shuffledPinDigits(random: SecureRandom = SecureRandom()): List<Char> {
    val digits = ('0'..'9').toMutableList()
    for (index in digits.lastIndex downTo 1) {
        val other = random.nextInt(index + 1)
        val previous = digits[index]
        digits[index] = digits[other]
        digits[other] = previous
    }
    return digits
}

internal fun appendPinDigit(value: String, digit: Char): String =
    if (value.length < PinLength && digit in '0'..'9' && value.all { it in '0'..'9' }) value + digit else value

/** The caller keeps the PIN only in memory and changes sessionKey for each authentication step. */
@Composable fun PinEntry(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "PIN 6자리",
    enabled: Boolean = true,
    sessionKey: Any? = Unit
) {
    val digits = remember(sessionKey) { shuffledPinDigits() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(sessionKey) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.heightIn(min = 36.dp).clearAndSetSemantics {
                contentDescription = "$label 입력 상태, ${value.length.coerceIn(0, PinLength)}/$PinLength 자리 입력됨"
                password()
                liveRegion = LiveRegionMode.Polite
            }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(PinLength) { position ->
                    Box(Modifier.size(16.dp)
                        .background(if (position < value.length) Carbon.Ink else Carbon.Surface, CircleShape)
                        .border(1.dp, if (position < value.length) Carbon.Ink else Carbon.Boundary, CircleShape))
                }
            }
        }
        Column(Modifier.fillMaxWidth().testTag("pin-keypad"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            digits.take(9).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { digit ->
                        PinKey(digit.toString(), Modifier.weight(1f), enabled && value.length < PinLength,
                            tag = "pin-digit-$digit") { onValueChange(appendPinDigit(value, digit)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PinKey("초기화", Modifier.weight(1f), enabled && value.isNotEmpty(),
                    description = "PIN 전체 지우기", tag = "pin-clear", digit = false) { onValueChange("") }
                val digit = digits.last()
                PinKey(digit.toString(), Modifier.weight(1f), enabled && value.length < PinLength,
                    tag = "pin-digit-$digit") { onValueChange(appendPinDigit(value, digit)) }
                PinKey("지우기", Modifier.weight(1f), enabled && value.isNotEmpty(),
                    description = "PIN 한 자리 지우기", tag = "pin-delete", digit = false) { onValueChange(value.dropLast(1)) }
            }
        }
    }
}

@Composable private fun PinKey(
    text: String,
    modifier: Modifier,
    enabled: Boolean,
    description: String? = null,
    tag: String,
    digit: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    FilledTonalButton(onClick, modifier.heightIn(min = 56.dp).testTag(tag)
        .then(if (focused) Modifier.border(2.dp, Carbon.Blue, RoundedCornerShape(16.dp)) else Modifier)
        .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description }),
        enabled = enabled, interactionSource = interaction, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Carbon.Layer, contentColor = Carbon.Ink,
            disabledContainerColor = Carbon.Layer.copy(alpha = .55f), disabledContentColor = Carbon.Muted)) {
        Text(text, style = if (digit) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge)
    }
}
