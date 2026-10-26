package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.AppModel
import com.copenhagen.wallet.BuildConfig
import com.copenhagen.wallet.Screen

@Composable fun CardEntryScreen(app: AppModel) {
    var number by remember(app.secretRevision) { mutableStateOf(TextFieldValue()) }
    var expiry by remember(app.secretRevision) { mutableStateOf(TextFieldValue()) }
    var cvc by remember(app.secretRevision) { mutableStateOf(TextFieldValue()) }
    var attempted by remember(app.secretRevision) { mutableStateOf(false) }
    val numberFocus = remember { FocusRequester() }
    val expiryFocus = remember { FocusRequester() }
    val cvcFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val enabled = !app.busy && BuildConfig.DEBUG && app.store.pg.isNotBlank()
    val errors = if (attempted) cardEntryErrors(number.text, expiry.text, cvc.text) else CardEntryErrors()
    val next = {
        if (enabled && app.route.screen == Screen.C01) {
            val currentErrors = cardEntryErrors(number.text, expiry.text, cvc.text)
            attempted = true
            when {
                currentErrors.number != null -> numberFocus.requestFocus()
                currentErrors.expiry != null -> expiryFocus.requestFocus()
                currentErrors.cvc != null -> cvcFocus.requestFocus()
                else -> {
                    val date = requireNotNull(cardExpiry(expiry.text))
                    app.cards.prepare(number.text, date.monthValue, date.year, cvc.text)
                    focus.clearFocus()
                    number = TextFieldValue(); expiry = TextFieldValue(); cvc = TextFieldValue()
                    app.show(Screen.C02)
                }
            }
        }
    }
    Page {
        Heading("새 카드 등록")
        if (!BuildConfig.DEBUG) Notice("현재 앱에서는 카드 등록을 사용할 수 없어요.")
        if (!enabled && !app.busy && BuildConfig.DEBUG) Notice("카드 등록 서버를 확인해 주세요.")
        CardNumberPrivacy {
            CardEntryField(number, { number = cardDigits(it, 19) }, "카드 번호", "0000 0000 •••• 0000",
                enabled, errors.number, numberFocus, CardNumberTransformation, secret = true,
                imeAction = ImeAction.Next, keyboardActions = KeyboardActions(onNext = { expiryFocus.requestFocus() }))
        }
        CardEntryField(expiry, { expiry = cardDigits(it, 4) }, "유효기간 (MM / YY)", "MM / YY",
            enabled, errors.expiry, expiryFocus, CardExpiryTransformation,
            imeAction = ImeAction.Next, keyboardActions = KeyboardActions(onNext = { cvcFocus.requestFocus() }))
        CardEntryField(cvc, { cvc = cardDigits(it, 4) }, "CVC", "",
            enabled, errors.cvc, cvcFocus, PasswordVisualTransformation(), secret = true,
            imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { next() }))
        Action("다음", enabled, "E18", next)
    }
}

@Composable private fun CardEntryField(
    value: TextFieldValue, onChange: (TextFieldValue) -> Unit, label: String, placeholder: String,
    enabled: Boolean, message: String?, focus: FocusRequester, transformation: VisualTransformation,
    secret: Boolean = false, imeAction: ImeAction, keyboardActions: KeyboardActions
) {
    Column {
        OutlinedTextField(value, onChange,
            Modifier.fillMaxWidth().focusRequester(focus).semantics {
                if (secret) password()
                if (message != null) error(message)
            },
            enabled = enabled, label = { Text(label) }, placeholder = { Text(placeholder) },
            singleLine = true, shape = RoundedCornerShape(16.dp), isError = message != null,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.NumberPassword else KeyboardType.Number,
                autoCorrectEnabled = false, imeAction = imeAction), keyboardActions = keyboardActions,
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Carbon.Surface,
                unfocusedContainerColor = Carbon.Surface, errorContainerColor = Carbon.Surface,
                disabledContainerColor = Carbon.Layer, unfocusedBorderColor = Carbon.Boundary, focusedBorderColor = Carbon.Blue))
        MotionReveal(message != null) {
            if (message != null) Text(message,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = Carbon.Error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Partial masking must not turn the old password field into a raw-PAN copy source. */
@Composable private fun CardNumberPrivacy(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val pasteOnly = remember(clipboard) { object : Clipboard by clipboard {
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    } }
    val toolbar = LocalTextToolbar.current
    val pasteToolbar = remember(toolbar) { object : TextToolbar by toolbar {
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            toolbar.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)
        }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?, onAutofillRequested: (() -> Unit)?) {
            toolbar.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, onAutofillRequested)
        }
    } }
    CompositionLocalProvider(LocalClipboard provides pasteOnly, LocalTextToolbar provides pasteToolbar, content = content)
}
