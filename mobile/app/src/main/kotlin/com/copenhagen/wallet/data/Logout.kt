package com.copenhagen.wallet.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class LogoutResult(val notificationsDisconnected: Boolean, val sessionRevoked: Boolean) {
    val warning: String? get() = when {
        !notificationsDisconnected && !sessionRevoked -> "로그아웃됨 · 알림 연결 해제·서버 세션 종료 미확인"
        !notificationsDisconnected -> "로그아웃됨 · 알림 연결 해제 미확인"
        !sessionRevoked -> "로그아웃됨 · 서버 세션 종료 미확인"
        else -> null
    }
}

/** 이전 계정의 인증으로 해제를 마친 뒤 세션을 폐기한다. 취소되어도 로컬 상태는 남기지 않는다. */
suspend fun endSession(
    disconnect: suspend () -> Unit,
    revoke: suspend () -> Unit,
    clear: () -> Unit
): LogoutResult {
    suspend fun attempt(action: suspend () -> Unit): Boolean = try {
        withTimeoutOrNull(10_000) { action(); true } ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    return try {
        val disconnected = attempt(disconnect)
        val revoked = attempt(revoke)
        LogoutResult(disconnected, revoked)
    } finally {
        clear()
    }
}
