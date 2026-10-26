package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.ApiError
import com.copenhagen.wallet.data.SessionExpired
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class BiometricRevocationTest {
    private class LocalState {
        var pendingKey: String? = null
        var registered = true
        var keyPresent = true
        val requests = mutableListOf<String>()
        suspend fun disable(response: suspend () -> Unit = {}) = revokeBiometricCredential(
            pendingKey,
            { pendingKey = it },
            { key ->
                assertEquals(pendingKey, key) // Durable before any network side effect.
                requests += key
                response()
            },
            { keyPresent = false; registered = false; pendingKey = null }
        )
    }

    @Test fun localCredentialSurvivesUntilTheServerConfirms() = runTest {
        val state = LocalState()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val job = launch { state.disable { started.complete(Unit); response.await() } }
        started.await()
        assertTrue(state.registered && state.keyPresent)
        assertEquals(state.pendingKey, UUID.fromString(state.pendingKey).toString())
        response.complete(Unit)
        job.join()
        assertFalse(state.registered || state.keyPresent)
        assertNull(state.pendingKey)
        assertEquals(1, state.requests.size)
    }

    @Test fun rejectionAndUnknownOutcomesKeepLocalStateWithoutAutomaticRetry() = runTest {
        for (failure in listOf(IOException(), ApiError(500, "INTERNAL_ERROR"),
            ApiError(400, "BIOMETRIC_CREDENTIAL_INVALID"), ApiError(403, "FORBIDDEN"), SessionExpired())) {
            val state = LocalState()
            assertSame(failure, runCatching { state.disable { throw failure } }.exceptionOrNull())
            assertTrue(state.registered && state.keyPresent)
            assertEquals(1, state.requests.size)
            assertEquals(state.pendingKey, state.requests.single())
        }
    }

    @Test fun lostResponseCanBeRetriedWithThePersistedKeyAfterRestart() = runTest {
        val beforeRestart = LocalState()
        runCatching { beforeRestart.disable { throw IOException("Response lost after revocation") } }
        val afterRestart = LocalState().apply { pendingKey = beforeRestart.pendingKey }
        afterRestart.disable() // Server returns the same 204, including for an already revoked credential.
        assertEquals(beforeRestart.requests.single(), afterRestart.requests.single())
        assertFalse(afterRestart.registered || afterRestart.keyPresent)
        assertNull(afterRestart.pendingKey)
    }

    @Test fun cancellationPreservesCredentialAndRetryIdentity() = runTest {
        val state = LocalState()
        val started = CompletableDeferred<Unit>()
        val job = launch { state.disable { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancelAndJoin()
        val key = state.pendingKey
        assertTrue(state.registered && state.keyPresent)
        state.disable()
        assertEquals(listOf(key, key), state.requests)
        assertNull(state.pendingKey)
    }

    @Test fun persistenceFailurePreventsTheRequestAndLocalDeletion() = runTest {
        var requested = false
        var cleared = false
        val failure = runCatching {
            revokeBiometricCredential(null, { throw IOException("storage failure") },
                { requested = true }, { cleared = true })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(requested || cleared)
    }

    @Test fun failedLocalCleanupStillUsesTheSameRemoteOperationOnRetry() = runTest {
        var persisted: String? = null
        val requests = mutableListOf<String>()
        val failure = runCatching {
            revokeBiometricCredential(persisted, { persisted = it }, { requests += it },
                { throw IOException("keystore failure") })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        val key = persisted
        revokeBiometricCredential(persisted, { persisted = it }, { requests += it }, { persisted = null })
        assertEquals(listOf(key, key), requests)
        assertNull(persisted)
    }

    @Test fun aNewEnrollmentGetsANewRevocationOperation() = runTest {
        val state = LocalState()
        state.disable()
        state.registered = true
        state.keyPresent = true
        state.disable()
        assertNotEquals(state.requests[0], state.requests[1])
        assertFalse(state.registered || state.keyPresent)
    }
}
