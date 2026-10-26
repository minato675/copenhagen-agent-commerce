package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.BiometricStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BiometricEnrollmentTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val registered = BiometricStatus(true, "ACTIVE", id)

    @Test fun orphanedCredentialIsRevokedBeforeNewRegistration() = runTest {
        for ((localId, keyPresent) in listOf(null to false, id to false, "other" to true)) {
            val calls = mutableListOf<String>()
            if (prepareBiometricEnrollment({ calls += "GET"; registered }, localId, keyPresent, false,
                    { calls += "DELETE" }, { error("Revocation owns local cleanup") })) calls += "POST"
            assertEquals(listOf("GET", "DELETE", "POST"), calls)
        }
    }

    @Test fun matchingLocalAndServerCredentialDoesNotReregister() = runTest {
        val needsRegistration = prepareBiometricEnrollment({ registered }, id, true, false,
            { error("Must retain active credential") }, { error("Must retain private key") })
        assertFalse(needsRegistration)
    }

    @Test fun failedReadOrRevocationNeverContinuesToRegistrationOrClearsLocalState() = runTest {
        for (readFails in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val result = runCatching {
                if (prepareBiometricEnrollment({ calls += "GET"; if (readFails) throw IOException(); registered },
                        id, false, false, { calls += "DELETE"; throw IOException() }, { calls += "CLEAR" })) calls += "POST"
            }
            assertTrue(result.isFailure)
            assertEquals(if (readFails) listOf("GET") else listOf("GET", "DELETE"), calls)
        }
    }

    @Test fun lostRevocationIsResolvedBeforeAnExplicitNewEnrollment() = runTest {
        val calls = mutableListOf<String>()
        assertTrue(prepareBiometricEnrollment({ BiometricStatus(false) }, id, true, true,
            { calls += "DELETE" }, { error("Revocation owns cleanup") }))
        assertEquals(listOf("DELETE"), calls)
    }

    @Test fun serverConfirmedAbsenceAllowsStaleLocalKeyCleanup() = runTest {
        var cleared = false
        assertTrue(prepareBiometricEnrollment({ BiometricStatus(false) }, id, true, false,
            { error("No remote credential") }, { cleared = true }))
        assertTrue(cleared)
    }
}
