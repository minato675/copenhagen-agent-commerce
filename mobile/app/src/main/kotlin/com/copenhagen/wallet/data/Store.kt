package com.copenhagen.wallet.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.copenhagen.wallet.BuildConfig
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 호스트·설정은 preferences, 토큰 쌍은 Android Keystore 키로 암호화한 단일 값. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("wallet.minimal", Context.MODE_PRIVATE)
    var core: String = prefs.getString("core", BuildConfig.CORE_BASE_URL).orEmpty(); private set
    var pay: String = prefs.getString("pay", BuildConfig.PAY_BASE_URL).orEmpty(); private set
    var pg: String = prefs.getString("pg", BuildConfig.PG_MOCK_BASE_URL).orEmpty(); private set
    val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }
    var loginId: String = prefs.getString("loginId", "").orEmpty(); private set
    var credentialId: String?
        get() = prefs.getString("credentialId", null)
        set(value) { prefs.edit().putString("credentialId", value).apply() }
    var biometricRevocationKey: String?
        get() = prefs.getString("biometricRevocationKey", null)
        set(value) {
            check(prefs.edit().putString("biometricRevocationKey", value).commit()) {
                "생체 인증 해제 요청을 저장하지 못했어요. 다시 시도해 주세요."
            }
        }
    var tokens: Tokens? = readTokens(); private set

    fun clearBiometricCredential() {
        check(prefs.edit().remove("credentialId").remove("biometricRevocationKey").commit()) {
            "생체 인증 해제 결과를 저장하지 못했어요. 다시 시도해 주세요."
        }
    }

    /** Only the unresolved operation reference survives process death; never PINs or assertions. */
    var unresolvedApprovalId: String?
        get() {
            if (tokens == null) return null
            val stored = prefs.getString("unresolvedApproval", null) ?: return null
            return runCatching {
                val bytes = Base64.getDecoder().decode(stored)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                }
                val raw = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
                try { raw.toString(Charsets.UTF_8).also { require(validId(it)) } }
                finally { raw.fill(0) }
            }.getOrElse { prefs.edit().remove("unresolvedApproval").commit(); null }
        }
        set(value) {
            if (value == null) {
                // A stale reference is harmless: its next read reconciles the known server result.
                // Cleanup must never interrupt logout's other security-state clearing.
                prefs.edit().remove("unresolvedApproval").commit()
                return
            }
            require(validId(value)) { "승인 요청 정보를 확인해 주세요." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val raw = value.toByteArray(Charsets.UTF_8)
            val encrypted = try { cipher.iv + cipher.doFinal(raw) } finally { raw.fill(0) }
            check(prefs.edit().putString("unresolvedApproval", Base64.getEncoder().encodeToString(encrypted)).commit()) {
                "승인 상태를 저장하지 못했어요."
            }
        }

    fun saveHosts(core: String, pay: String, pg: String) {
        this.core = core; this.pay = pay; this.pg = pg
        prefs.edit().putString("core", core).putString("pay", pay).putString("pg", pg).apply()
    }
    var unresolvedPurchase: PurchaseResume?
        get() {
            if (tokens == null) return null
            val stored = prefs.getString("unresolvedPurchase", null) ?: return null
            return runCatching {
                val bytes = Base64.getDecoder().decode(stored)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                }
                val raw = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
                try { json.decodeFromString(PurchaseResume.serializer(), raw.toString(Charsets.UTF_8)) }
                finally { raw.fill(0) }
            }.getOrElse { prefs.edit().remove("unresolvedPurchase").commit(); null }
        }
        set(value) {
            val encrypted = value?.let {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                val raw = json.encodeToString(PurchaseResume.serializer(), it).toByteArray(Charsets.UTF_8)
                try { Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(raw)) }
                finally { raw.fill(0) }
            }
            check(prefs.edit().putString("unresolvedPurchase", encrypted).commit()) { "배송지 선택 상태를 저장하지 못했어요." }
        }
    fun saveTokens(value: Tokens, loginId: String = this.loginId) {
        require(value.accessToken.isNotBlank() && value.refreshToken.isNotBlank()) { "인증 응답을 확인할 수 없어요." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val raw = json.encodeToString(Tokens.serializer(), value).toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.iv + cipher.doFinal(raw) } finally { raw.fill(0) }
        check(prefs.edit().putString("tokens", Base64.getEncoder().encodeToString(encrypted))
            .putString("loginId", loginId).commit()) { "로그인을 저장하지 못했어요." }
        tokens = value; this.loginId = loginId
    }
    fun clearSession() {
        tokens = null
        prefs.edit().remove("tokens").remove("credentialId").remove("biometricRevocationKey")
            .remove("unresolvedApproval").remove("unresolvedPurchase").commit()
    }
    private fun readTokens(): Tokens? {
        val stored = prefs.getString("tokens", null) ?: return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            val raw = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            try { json.decodeFromString(Tokens.serializer(), raw.toString(Charsets.UTF_8)) }
            finally { raw.fill(0) }
        }.getOrElse { prefs.edit().remove("tokens").commit(); null }
    }
    private fun key(): SecretKey {
        val alias = "copenhagen.session.minimal"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
