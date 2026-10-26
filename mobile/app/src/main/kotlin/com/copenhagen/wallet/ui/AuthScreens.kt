package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*
import com.copenhagen.wallet.R
import com.copenhagen.wallet.data.*

@Composable fun AuthScreen(app: AppModel, activity: FragmentActivity) = Page {
    val screen = LocalDisplayedRoute.current.screen
    // Credentials intentionally never enter saved instance state.
    var password by remember(app.secretRevision, screen) { mutableStateOf("") }
    var passwordVisible by remember(app.secretRevision, screen) { mutableStateOf(false) }
    when (screen) {
        Screen.A01 -> {
            AuthIdentity(null, "반가워요")
            Field(app.loginId, { app.loginId = it }, "아이디", enabled = !app.busy, maxLength = 20)
            LoginIdGuidance(app.loginId)
            Action("다음", !app.busy && validLoginId(app.loginId), "E01") { app.show(Screen.A02) }
            Secondary("회원가입", !app.busy) { app.show(Screen.A03) }
        }
        Screen.A02, Screen.A08 -> {
            AuthIdentity(if (screen == Screen.A08) WalletIcon.Shield else null, if (screen == Screen.A08) "본인 확인" else "로그인")
            Surface(color = Carbon.Layer, shape = RoundedCornerShape(16.dp)) {
                Text(app.loginId, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
            Field(password, { password = it }, "비밀번호", secret = true, keyboard = KeyboardType.Password,
                enabled = !app.busy, maxLength = 20, revealSecret = passwordVisible, trailingIcon = {
                    IconButton({ passwordVisible = !passwordVisible }, enabled = !app.busy,
                        modifier = Modifier.size(48.dp).semantics {
                            stateDescription = if (passwordVisible) "표시됨" else "숨겨짐"
                        }) {
                        Glyph(if (passwordVisible) WalletIcon.EyeOff else WalletIcon.Eye,
                            if (passwordVisible) "비밀번호 숨기기" else "비밀번호 표시", tint = Carbon.Muted)
                    }
                })
            Action(if (screen == Screen.A08) "확인" else "로그인", !app.busy && password.isNotBlank(), "E03") {
                val value = password; password = ""; passwordVisible = false
                app.login(value, reauthenticate = screen == Screen.A08)
            }
        }
        Screen.A03 -> Signup(app)
        Screen.A04 -> {
            AuthIdentity(WalletIcon.Shield, "보안 확인")
            Action("다시 확인", !app.busy) { app.execute { app.checkPin() } }
            Secondary("로그아웃", !app.busy) { app.logout() }
        }
        Screen.A05, Screen.A06 -> {
            AuthIdentity(WalletIcon.Shield, if (screen == Screen.A05) "결제 PIN 등록" else "PIN 다시 입력")
            PinEntry(password, { password = it }, enabled = !app.busy, sessionKey = app.secretRevision to screen)
            Action(if (screen == Screen.A05) "다음" else "등록", !app.busy && validPin(password)) {
                val value = password; password = ""
                if (screen == Screen.A05) app.setFirstPin(value) else app.confirmPin(value)
            }
        }
        Screen.A07 -> {
            AuthIdentity(WalletIcon.Shield, "생체 인증")
            if (!app.biometrics.available(activity)) Notice("이 기기에서는 생체 인증을 사용할 수 없어요.")
            Action(if (app.biometricRegistered) "등록됨" else "생체 인증 등록", !app.busy && !app.biometricRegistered && app.biometrics.available(activity)) {
                app.enroll(activity)
            }
            Secondary("나중에", !app.busy) { app.execute { app.enterHome() } }
        }
        Screen.P06 -> {
            AuthIdentity(WalletIcon.Shield, "인증이 잠겼어요")
            Action("다시 확인", !app.busy) { app.execute { app.checkPin() } }
            Secondary("로그아웃", !app.busy) { app.logout() }
        }
        else -> Unit
    }
}

@Composable private fun AuthIdentity(icon: WalletIcon?, title: String, detail: String? = null) {
    Spacer(Modifier.height(12.dp))
    if (icon == null) BrandLogo(size = 64.dp, contentDescription = stringResource(R.string.brand_logo_description))
    else Surface(color = Carbon.Layer, shape = RoundedCornerShape(20.dp)) {
        Glyph(icon, modifier = Modifier.padding(16.dp).size(32.dp), tint = Carbon.Blue)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading(title)
        detail?.let { Text(it, color = Carbon.Muted, style = MaterialTheme.typography.bodyLarge) }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable private fun Signup(app: AppModel) {
    var id by rememberSaveable { mutableStateOf("") }
    var password by remember(app.secretRevision) { mutableStateOf("") }
    var confirmation by remember(app.secretRevision) { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    Heading("회원가입")
    Field(id, { id = it }, "아이디", maxLength = 20, enabled = !app.busy)
    LoginIdGuidance(id)
    Field(password, { password = it }, "비밀번호", secret = true, keyboard = KeyboardType.Password, maxLength = 20, enabled = !app.busy)
    val validation = passwordError(password, id)
    MotionReveal(password.isNotBlank() && validation != null) {
        Text(validation.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = Carbon.Error, style = MaterialTheme.typography.bodyMedium)
    }
    Field(confirmation, { confirmation = it }, "비밀번호 확인", secret = true, keyboard = KeyboardType.Password, maxLength = 20, enabled = !app.busy)
    MotionReveal(confirmation.isNotBlank() && confirmation != password) {
        Text("비밀번호가 일치하지 않아요.", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = Carbon.Error, style = MaterialTheme.typography.bodyMedium)
    }
    Field(email, { email = it }, "이메일", keyboard = KeyboardType.Email, maxLength = 254, enabled = !app.busy)
    Field(name, { name = it }, "이름", maxLength = 20, enabled = !app.busy)
    Action("가입", !app.busy && validLoginId(id) && validEmail(email) && name.isNotBlank() &&
        validation == null && password == confirmation, "E04") {
        app.execute {
            app.api.signup(id, password, email.trim(), name.trim())
            password = ""; confirmation = ""; app.loginId = id
            id = ""; email = ""; name = ""
            app.show(Screen.A01, replace = true)
        }
    }
}

private enum class PinChangeStep { CURRENT, NEW, CONFIRM }

@Composable fun ChangePinScreen(app: AppModel) = Page {
    var current by remember(app.secretRevision) { mutableStateOf("") }
    var next by remember(app.secretRevision) { mutableStateOf("") }
    var entered by remember(app.secretRevision) { mutableStateOf("") }
    var step by remember(app.secretRevision) { mutableStateOf(PinChangeStep.CURRENT) }
    var key by remember(app.secretRevision) { mutableStateOf(newKey()) }
    val label = when (step) {
        PinChangeStep.CURRENT -> "현재 PIN"
        PinChangeStep.NEW -> "새 PIN"
        PinChangeStep.CONFIRM -> "새 PIN 확인"
    }
    AuthIdentity(WalletIcon.Shield, "PIN 변경", when (step) {
        PinChangeStep.CURRENT -> "현재 사용 중인 PIN 6자리를 입력해 주세요."
        PinChangeStep.NEW -> "현재 PIN과 다른 6자리를 입력해 주세요."
        PinChangeStep.CONFIRM -> "새 PIN을 한 번 더 입력해 주세요."
    })
    Text("${step.ordinal + 1}/3 단계", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
    PinEntry(entered, { entered = it }, label, enabled = !app.busy, sessionKey = app.secretRevision to step)
    val sameAsCurrent = step == PinChangeStep.NEW && validPin(entered) && current == entered
    val confirmationMismatch = step == PinChangeStep.CONFIRM && validPin(entered) && entered != next
    MotionReveal(sameAsCurrent) {
        Text("현재 PIN과 다른 번호를 입력해 주세요.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Carbon.Error)
    }
    MotionReveal(confirmationMismatch) {
        Text("새 PIN이 일치하지 않아요.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Carbon.Error)
    }
    Action(if (step == PinChangeStep.CONFIRM) "PIN 변경" else "다음",
        !app.busy && validPin(entered) && !sameAsCurrent && !confirmationMismatch) {
        when (step) {
            PinChangeStep.CURRENT -> { current = entered; entered = ""; step = PinChangeStep.NEW }
            PinChangeStep.NEW -> { next = entered; entered = ""; key = newKey(); step = PinChangeStep.CONFIRM }
            PinChangeStep.CONFIRM -> { entered = ""; app.changePin(current, next, key) }
        }
    }
    if (step != PinChangeStep.CURRENT) Secondary("처음부터 다시 입력", !app.busy) {
        current = ""; next = ""; entered = ""; key = newKey(); step = PinChangeStep.CURRENT
    }
}

@Composable private fun LoginIdGuidance(value: String) {
    MotionReveal(value.isNotBlank() && !validLoginId(value)) {
        Text("영문 소문자, 숫자, _ 또는 -로 5~20자를 입력해 주세요.",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
