package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.BiometricStatus

/** Called after the user's enrollment prompt; a failed read/revocation must stop registration. */
suspend fun prepareBiometricEnrollment(
    readStatus: suspend () -> BiometricStatus,
    localCredentialId: String?, keyPresent: Boolean, revocationPending: Boolean,
    revoke: suspend () -> Unit, clearLocal: () -> Unit
): Boolean {
    val remote = readStatus().also { it.requireValid() }
    if (remote.matches(localCredentialId, keyPresent) && !revocationPending) return false
    if (remote.registered || revocationPending) revoke()
    else if (localCredentialId != null) clearLocal()
    return true
}
