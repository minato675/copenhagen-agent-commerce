package com.copenhagen.wallet.data

/** A push is an entry reference, never evidence of consent or of a completed operation. */
data class RequestTarget(val kind: Kind, val id: String, val paymentId: String? = null) {
    enum class Kind(val path: String) { APPROVAL("approvals"), PURCHASE("purchases"), PAYMENT_RESULT("payment-results") }
    init {
        require(validId(id))
        require(if (kind == Kind.PAYMENT_RESULT) paymentId?.let(::validId) == true else paymentId == null)
    }
    val identity get() = "${kind.path}/${paymentId ?: id}"
    val title get() = when (kind) {
        Kind.PURCHASE -> "배송지 선택"
        Kind.APPROVAL -> "승인 요청"
        Kind.PAYMENT_RESULT -> "결제 결과"
    }

    companion object {
        fun fromPayload(data: Map<String, String>): RequestTarget? {
            val approval = data["approvalId"].orEmpty().takeIf { it.isNotBlank() }
            val purchase = data["purchaseRequestId"].orEmpty().takeIf { it.isNotBlank() }
            // Firebase sends empty strings for the other identifier. Conflicting IDs fail closed.
            if (approval != null && purchase != null) return null
            val action = data["nextAction"].orEmpty().ifBlank {
                when (data["type"]) {
                    "PURCHASE_HANDOFF" -> "OPEN_PURCHASE"
                    "APPROVAL_REQUESTED" -> "OPEN_APPROVAL"
                    "PAYMENT_RESULT" -> "SHOW_PAYMENT_RESULT"
                    null, "" -> if (purchase != null) "OPEN_PURCHASE" else "OPEN_APPROVAL"
                    else -> return null
                }
            }
            return when (action) {
                "OPEN_PURCHASE" -> purchase?.takeIf(::validId)?.let { RequestTarget(Kind.PURCHASE, it) }
                "OPEN_APPROVAL" -> approval?.takeIf(::validId)?.let { RequestTarget(Kind.APPROVAL, it) }
                "SHOW_PAYMENT_RESULT", "WAIT_FOR_PAYMENT_STATUS" -> {
                    val payment = data["paymentId"]?.takeIf(::validId) ?: return null
                    approval?.takeIf(::validId)?.let { RequestTarget(Kind.PAYMENT_RESULT, it, payment) }
                }
                else -> null
            }
        }
    }
}
