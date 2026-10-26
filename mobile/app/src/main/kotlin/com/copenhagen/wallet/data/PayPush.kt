package com.copenhagen.wallet.data

/** The data-only wire contract. Notification text is a hint; the opened screen reads server state. */
data class PayPush(val eventId: String, val target: RequestTarget, val status: String) {
    val terminal get() = target.kind == RequestTarget.Kind.PAYMENT_RESULT && status in setOf("SUCCESS", "FAILED")
    val channel get() = when (target.kind) {
        RequestTarget.Kind.PURCHASE -> "purchase_handoffs"
        RequestTarget.Kind.APPROVAL -> "approvals" // Keep existing user channel preferences.
        RequestTarget.Kind.PAYMENT_RESULT -> "payment_results"
    }
    val title get() = when (target.kind) {
        RequestTarget.Kind.PURCHASE -> "배송지 선택 요청"
        RequestTarget.Kind.APPROVAL -> "결제 승인 요청"
        RequestTarget.Kind.PAYMENT_RESULT -> when (status) {
            "SUCCESS" -> "결제 완료"
            "FAILED" -> "결제 실패"
            else -> "결제 결과 확인 중"
        }
    }
    val body get() = when (target.kind) {
        RequestTarget.Kind.PURCHASE -> "받으실 배송지를 선택해 주세요."
        RequestTarget.Kind.APPROVAL -> "결제 요청을 확인해 주세요."
        RequestTarget.Kind.PAYMENT_RESULT -> when (status) {
            "SUCCESS" -> "결제가 완료되었어요."
            "FAILED" -> "결제를 완료하지 못했어요."
            else -> "결제 상태를 확인해 주세요."
        }
    }

    companion object {
        val fields = listOf("eventId", "pushId", "type", "approvalId", "purchaseRequestId", "paymentId", "status", "nextAction")
        fun fromPayload(data: Map<String, String>): PayPush? {
            if (!fields.all(data::containsKey)) return null
            val event = data.getValue("eventId")
            if (event.isBlank() || event.length > 256 || data["pushId"] != event) return null
            val target = RequestTarget.fromPayload(data) ?: return null
            val status = data.getValue("status")
            val valid = when (target.kind) {
                RequestTarget.Kind.PURCHASE -> data["type"] == "PURCHASE_HANDOFF" && data["nextAction"] == "OPEN_PURCHASE" &&
                    data["approvalId"] == "" && data["paymentId"] == "" && status == ""
                RequestTarget.Kind.APPROVAL -> data["type"] == "APPROVAL_REQUESTED" && data["nextAction"] == "OPEN_APPROVAL" &&
                    data["purchaseRequestId"] == "" && data["paymentId"] == "" && status == ""
                RequestTarget.Kind.PAYMENT_RESULT -> data["type"] == "PAYMENT_RESULT" && data["purchaseRequestId"] == "" &&
                    data["nextAction"] in setOf("SHOW_PAYMENT_RESULT", "WAIT_FOR_PAYMENT_STATUS")
            }
            if (!valid) return null
            // WAIT always stays pending, even if a malformed payload also claims SUCCESS.
            return PayPush(event, target, if (data["nextAction"] == "WAIT_FOR_PAYMENT_STATUS") "UNKNOWN" else status)
        }
    }
}

/** Result labels accept authenticated Core purchase outcomes as well as legacy Pay session states. */
fun paymentResultTitle(approvalStatus: String) = when (approvalStatus) {
    "COMPLETED" -> "결제가 완료되었어요"
    "PARTIALLY_COMPLETED" -> "일부 결제만 완료되었어요"
    "INVALIDATED", "FAILED" -> "결제를 완료하지 못했어요"
    "CANCELLED" -> "결제가 취소되었어요"
    "REJECTED" -> "요청을 거절했어요"
    "EXPIRED" -> "요청이 만료되었어요"
    else -> "결제 결과 확인 중"
}
fun paymentResultPending(approvalStatus: String) = approvalStatus !in setOf(
    "COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED", "INVALIDATED", "REJECTED", "EXPIRED")
