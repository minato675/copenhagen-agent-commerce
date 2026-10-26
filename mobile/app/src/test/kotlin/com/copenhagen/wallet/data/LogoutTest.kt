package com.copenhagen.wallet.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LogoutTest {
    @Test fun disconnectUsesOldSessionBeforeRevocationAndCleanup() = runBlocking {
        val calls = mutableListOf<String>()
        var account: String? = "old-account"
        val result = endSession(
            disconnect = { calls += "disconnect:$account" },
            revoke = { calls += "revoke:$account" },
            clear = { account = null; calls += "clear" }
        )
        assertEquals(listOf("disconnect:old-account", "revoke:old-account", "clear"), calls)
        assertNull(account)
        assertNull(result.warning)
    }

    @Test fun failedDisconnectStillRevokesAndClearsWithoutRetrying() = runBlocking {
        var attempts = 0
        var revoked = false
        var cleared = false
        val result = endSession(
            disconnect = { attempts++; throw IOException() },
            revoke = { revoked = true },
            clear = { cleared = true }
        )
        assertEquals(1, attempts)
        assertTrue(revoked && cleared)
        assertFalse(result.notificationsDisconnected)
        assertTrue(result.sessionRevoked)
        assertNotNull(result.warning)
    }

    @Test fun revokedSessionFailureIsReportedAfterLocalCleanup() = runBlocking {
        var cleared = false
        val result = endSession({}, { throw IOException() }, { cleared = true })
        assertTrue(cleared && result.notificationsDisconnected)
        assertFalse(result.sessionRevoked)
        assertNotNull(result.warning)
    }

    @Test fun stalledDisconnectTimesOutAndStillRevokesAndClears() = runBlocking {
        var revoked = false
        var cleared = false
        val result = endSession({ delay(Long.MAX_VALUE) }, { revoked = true }, { cleared = true })
        assertFalse(result.notificationsDisconnected)
        assertTrue(result.sessionRevoked && revoked && cleared)
    }

    @Test fun bothRemoteFailuresAreReportedAfterLocalCleanup() = runBlocking {
        var cleared = false
        val result = endSession({ throw IOException() }, { throw IOException() }, { cleared = true })
        assertTrue(cleared)
        assertFalse(result.notificationsDisconnected || result.sessionRevoked)
        assertTrue(result.warning!!.contains("알림 연결 해제"))
        assertTrue(result.warning!!.contains("서버 세션 종료"))
    }

    @Test fun cancellationDuringDisconnectClearsAndDoesNotStartAnotherRequest() = runBlocking {
        var revoked = false
        var cleared = false
        val failure = runCatching {
            endSession({ throw CancellationException() }, { revoked = true }, { cleared = true })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(cleared)
        assertFalse(revoked)
    }

    @Test fun cancellationDuringRevocationClearsLocalSession() = runBlocking {
        var cleared = false
        val failure = runCatching {
            endSession({}, { throw CancellationException() }, { cleared = true })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(cleared)
    }
}
