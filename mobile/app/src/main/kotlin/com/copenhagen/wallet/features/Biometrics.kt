package com.copenhagen.wallet.features

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A dismissed system prompt is a switch to PIN, not a failed payment. */
class BiometricPromptFailure(val code: Int) : IllegalStateException("생체 인증을 완료하지 못했어요.") {
    val cancelled get() = code in setOf(BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED)
}

class Biometrics {
    private val alias = "copenhagen.biometric.p256"
    private var registrationKey = newKey()
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun available(activity: FragmentActivity) = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    fun clear() { keystore().deleteEntry(alias); registrationKey = newKey() }
    fun hasKey() = runCatching { keystore().getKey(alias, null) is PrivateKey }.getOrDefault(false)

    suspend fun status(api: Api) = api.pay("GET", "/users/me/biometric-credentials", deviceHeader = true)
        .read<BiometricStatus>().also { it.requireValid() }

    suspend fun enroll(activity: FragmentActivity, api: Api, store: Store, revoke: suspend () -> Unit) {
        prompt(activity)
        // Enrollment is explicit. Remove an orphaned server credential before replacing its key.
        val needsRegistration = prepareBiometricEnrollment({ status(api) }, store.credentialId, hasKey(),
            store.biometricRevocationKey != null, revoke) { clear(); store.clearBiometricCredential() }
        if (!needsRegistration) return
        val publicKey = keystore().getCertificate(alias)?.publicKey ?: createKey()
        val response = api.pay("POST", "/users/me/biometric-credentials", buildJsonObject {
            put("deviceId", store.deviceId)
            put("publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded))
            put("credentialType", "PLATFORM_BIOMETRIC")
        }, registrationKey).read<BiometricCredential>()
        store.credentialId = response.credentialId
    }
    suspend fun sign(activity: FragmentActivity, challenge: Challenge): String {
        check(java.time.Instant.parse(challenge.expiresAt).isAfter(java.time.Instant.now())) { "생체 인증 요청이 만료되었어요." }
        val key = keystore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("생체 인증을 다시 등록해 주세요.")
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        val authenticated = requireNotNull(prompt(activity, signature))
        // Base64URL처럼 보이더라도 challenge는 디코드하지 않는다. Pay v1.7의 UTF-8 원문 서명 규약.
        authenticated.update(challenge.challenge.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(authenticated.sign())
    }
    private fun createKey(): PublicKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else { @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1) }
        return KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair().public
    }
    private suspend fun prompt(activity: FragmentActivity, signature: Signature? = null): Signature? {
        // AndroidX silently skips authenticate() after state saving and sends no callback.
        // Release the in-flight action if a read finished after this host left or rotated.
        if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            throw BiometricPromptFailure(BiometricPrompt.ERROR_CANCELED)
        check(available(activity)) { "기기에 생체 인증을 등록해 주세요." }
        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    if (signature != null && result.cryptoObject?.signature == null)
                        continuation.resumeWithException(IllegalStateException("생체 인증을 완료하지 못했어요."))
                    else continuation.resume(result.cryptoObject?.signature)
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (continuation.isActive) continuation.resumeWithException(BiometricPromptFailure(code))
                }
            })
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            val info = BiometricPrompt.PromptInfo.Builder().setTitle("Copenhagen")
                .setSubtitle(if (signature == null) "본인 인증" else "결제 승인")
                .setNegativeButtonText(if (signature == null) "취소" else "PIN으로 결제하기")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
            if (signature == null) prompt.authenticate(info)
            else prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
        }
    }
}
