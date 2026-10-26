package com.copenhagen.wallet.data

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Purchase(
    val purchaseRequestId: String, val status: String, val cartId: String, val cartVersion: Int,
    val expiresAt: String, val nextAction: String, val failureCode: String? = null,
    val merchantCheckouts: List<PurchaseCheckout> = emptyList()
) {
    fun expired(now: Instant = Instant.now()) = !Instant.parse(expiresAt).isAfter(now)
    val needsAddress get() = status == "PENDING_ADDRESS" && nextAction == "SELECT_SHIPPING_ADDRESS"
    val preparing get() = status == "CREATING_CHECKOUTS" && nextAction == "WAIT"
    val approvalIds get() = if (nextAction == "OPEN_APPROVAL") merchantCheckouts
        .filter { it.status == "AWAITING_APPROVAL" }.mapNotNull { it.approvalId?.takeIf(::validId) }.distinct() else emptyList()
    val terminal get() = status in setOf("COMPLETED", "PARTIALLY_COMPLETED", "CANCELLED", "EXPIRED", "REJECTED", "FAILED")
}

@Serializable
data class PurchaseCheckout(val merchantId: String, val approvalId: String? = null, val status: String,
    val totalAmount: Long, val currency: String)

/** Encrypted recovery journal: references and the exact command only, no address text or credentials. */
@Serializable
data class PurchaseResume(val purchaseId: String, val addressId: String, val cartVersion: Int, val key: String) {
    init { require(validId(purchaseId) && validId(addressId) && validId(key) && cartVersion >= 0) }
}

fun purchaseStatusLabel(status: String): String = when (status) {
    "PENDING_ADDRESS" -> "배송지 선택"
    "CREATING_CHECKOUTS" -> "결제 요청 준비 중"
    "AWAITING_APPROVAL" -> "결제 승인 대기"
    "COMPLETING" -> "결제 처리 중"
    "COMPLETED" -> "구매 완료"
    "PARTIALLY_COMPLETED" -> "일부 구매 완료"
    "CANCELLED" -> "구매 취소됨"
    "EXPIRED" -> "요청이 만료되었어요"
    "REJECTED" -> "구매 요청 거절됨"
    "FAILED" -> "구매를 진행하지 못했어요"
    else -> "상태 확인 필요"
}
