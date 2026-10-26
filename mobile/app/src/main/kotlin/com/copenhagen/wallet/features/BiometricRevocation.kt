package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.newKey

/** Keep the same operation across a lost response or process restart; never discard the key first. */
suspend fun revokeBiometricCredential(
    pendingKey: String?,
    persistKey: (String) -> Unit,
    revoke: suspend (String) -> Unit,
    clearLocal: () -> Unit
) {
    val key = pendingKey ?: newKey().also(persistKey)
    revoke(key)
    // The caller clears the credential and pending key together, only after server confirmation.
    clearLocal()
}
