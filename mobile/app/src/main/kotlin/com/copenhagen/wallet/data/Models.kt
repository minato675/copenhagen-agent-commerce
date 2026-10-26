package com.copenhagen.wallet.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNames
import java.time.Instant

@Serializable
data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long) {
    override fun toString() = "Tokens([redacted])"
}
@Serializable data class PinStatus(val registered: Boolean, val status: String)
@Serializable data class Items<T>(val items: List<T>, val nextCursor: String? = null)
@Serializable
data class Card(
    @SerialName("paymentMethodId") val id: String,
    val brand: String? = null, val last4: String? = null,
    val expiryMonth: Int? = null, val expiryYear: Int? = null,
    val isDefault: Boolean, val status: String
) {
    val active get() = status == "ACTIVE"
    val label get() = "${brand.orEmpty()} •••• ${last4 ?: "----"}".trim()
}
@Serializable
data class TokenizedCard(
    val provider: String, val providerTokenRef: String, val brand: String,
    val last4: String, val expiryMonth: Int, val expiryYear: Int
) { override fun toString() = "TokenizedCard([redacted])" }
@Serializable data class BiometricCredential(val credentialId: String)
@Serializable
data class BiometricStatus(val registered: Boolean, val status: String? = null, val credentialId: String? = null) {
    fun requireValid() {
        require(if (registered) status == "ACTIVE" && credentialId?.let(::validId) == true
            else status == null && credentialId == null) { "생체 인증 등록 상태를 확인할 수 없어요." }
    }
    fun matches(localCredentialId: String?, keyPresent: Boolean) =
        registered && status == "ACTIVE" && credentialId != null && credentialId == localCredentialId && keyPresent
}
@Serializable data class Challenge(val challengeId: String, val challenge: String, val expiresAt: String)
@Serializable data class ItemSummary(val name: String, val option: String? = null, val quantity: Int = 1,
    val amountMinor: Long? = null, val imageUrl: String? = null,
    // Order v2 carries stable catalog identities; Pay summaries and legacy orders omit them.
    val merchantId: String? = null, val merchantProductId: String? = null, val merchantVariantId: String? = null)
@Serializable
data class ApprovalMerchant(
    val merchantId: String, val merchantName: String, val checkoutId: String, val checkoutHash: String,
    val summary: List<ItemSummary>, val subtotalAmountMinor: Long
)
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Approval(
    @SerialName("approvalId") val id: String,
    // Legacy flat fields remain readable during a staggered server rollout.
    val merchantName: String = "", val checkoutId: String = "",
    // Current list and detail both use totalAmountMinor; older lists use amountMinor.
    @JsonNames("totalAmountMinor") val amountMinor: Long, val currency: String, val status: String,
    val requiredAction: String? = null, val expiresAt: String,
    val selectedPaymentMethodId: String? = null, val checkoutHash: String? = null,
    val summary: List<ItemSummary> = emptyList(), val biometricChallenge: Challenge? = null,
    val merchants: List<ApprovalMerchant> = emptyList(), val merchantCount: Int = 1
) {
    val displayName get(): String {
        val name = merchants.firstOrNull()?.merchantName ?: merchantName
        val count = merchants.size.takeIf { it > 0 } ?: merchantCount
        return if (count > 1) "$name 외 ${count - 1}곳" else name
    }
    val displaySummary get() = if (merchants.isEmpty()) summary else merchants.flatMap { it.summary }
    /** A list row has no checkout. Only a complete detail can become an authorization review. */
    fun requireDetail() {
        require(amountMinor >= 0)
        if (merchants.isEmpty()) {
            require(merchantName.isNotBlank() && checkoutId.isNotBlank() && !checkoutHash.isNullOrBlank()) {
                "승인 요청의 판매처 정보를 확인할 수 없어요."
            }
        } else {
            require(merchants.map { it.merchantId }.distinct().size == merchants.size)
            require(merchants.all { it.merchantId.isNotBlank() && it.merchantName.isNotBlank() &&
                it.checkoutId.isNotBlank() && it.checkoutHash.isNotBlank() && it.subtotalAmountMinor >= 0 &&
                it.summary.isNotEmpty() }) { "승인 요청의 판매처 정보를 확인할 수 없어요." }
            require(merchants.fold(0L) { sum, merchant -> Math.addExact(sum, merchant.subtotalAmountMinor) } == amountMinor) {
                "승인 요청의 금액을 확인할 수 없어요."
            }
        }
    }
    val actionable get() = status in setOf("PENDING", "PAYMENT_METHOD_REQUIRED")
    fun expired(now: Instant = Instant.now()) = !Instant.parse(expiresAt).isAfter(now)
}
@Serializable
data class ApprovalResult(val approvalId: String, val status: String, val requiredAction: String? = null)

// Core v5 response contracts (develop 8de6480).
@Serializable
data class Address(
    @SerialName("addressId") val id: String, val recipient: String, val phone: String,
    val postalCode: String, val address: String, val detail: String = "", val memo: String = "",
    val isDefault: Boolean = false
)
@Serializable
data class AddressForm(
    val recipient: String = "", val phone: String = "", val postalCode: String = "",
    val address: String = "", val detail: String = "", val memo: String = ""
) {
    val valid get() = recipient.isNotBlank() && phone.isNotBlank() && postalCode.isNotBlank() && address.isNotBlank()
}
@Serializable data class Profile(val name: String, val loginId: String, val email: String)
@Serializable
data class OrderAddress(
    val recipient: String, val phone: String, val postalCode: String,
    val address: String, val detail: String = "", val memo: String = ""
)
@Serializable
data class Order(
    @SerialName("orderId") val id: String, val title: String, val status: String,
    val amountMinor: Long, val currency: String, val createdAt: String,
    val items: List<ItemSummary> = emptyList(), val address: OrderAddress? = null, val paymentLabel: String = ""
)
