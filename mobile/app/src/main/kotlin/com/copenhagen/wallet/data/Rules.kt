package com.copenhagen.wallet.data

import java.net.URI
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

// PG and Pay accept a canonical UUID only; operation names must not enter the header.
fun newKey() = UUID.randomUUID().toString()
fun validId(value: String) = value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
fun validPin(value: String) = value.matches(Regex("[0-9]{6}"))
fun validLoginId(value: String) = value.matches(Regex("[a-z0-9_-]{5,20}"))
fun validEmail(value: String) = value.length <= 254 && value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
fun passwordError(value: String, loginId: String): String? {
    if (value.length !in 8..20 || value.any { it.code !in 33..126 }) return "비밀번호는 공백 없이 8~20자로 입력해 주세요."
    val kinds = listOf(value.any(Char::isLetter), value.any(Char::isDigit), value.any { !it.isLetterOrDigit() })
    if (kinds.count { it } < 2) return "영문, 숫자, 특수문자 중 두 가지 이상을 사용해 주세요."
    if (value.equals(loginId, true)) return "아이디와 다른 비밀번호를 입력해 주세요."
    if (value.lowercase().windowed(3).any { w ->
        w.toSet().size == 1 || (w[1] - w[0] == 1 && w[2] - w[1] == 1) ||
            (w[1] - w[0] == -1 && w[2] - w[1] == -1)
    }) return "같거나 연속된 문자 세 개는 사용할 수 없어요."
    return null
}
fun normalizeCardNumber(value: String) = value.replace(" ", "").replace("-", "")
fun validCardNumber(value: String): Boolean {
    if (!value.matches(Regex("[0-9]{13,19}"))) return false
    return value.reversed().mapIndexed { index, c ->
        val digit = c - '0'
        if (index % 2 == 0) digit else (digit * 2).let { if (it > 9) it - 9 else it }
    }.sum() % 10 == 0
}
fun validExpiry(month: Int, year: Int, now: YearMonth = YearMonth.now()): Boolean =
    year in 1000..9999 && month in 1..12 && !YearMonth.of(year, month).isBefore(now)
fun validCvc(value: String) = value.matches(Regex("[0-9]{3,4}"))
fun normalizeHost(input: String, debug: Boolean): String {
    val uri = runCatching { URI(input.trim()) }.getOrNull()
    require(uri != null && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "서버 주소를 확인해 주세요."
    }
    require(uri.scheme == "https" || (debug && uri.scheme == "http")) { "HTTPS 주소를 입력해 주세요." }
    require(uri.port == -1 || uri.port in 1..65535) { "포트를 확인해 주세요." }
    return uri.toASCIIString().trimEnd('/') + "/"
}
fun money(value: Long) = NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + "원"
fun money(value: Long, currency: String) = if (currency == "KRW") money(value)
    else NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + " " + currency
val approvalStatuses = listOf("PAYMENT_METHOD_REQUIRED", "PENDING", "APPROVED", "COMPLETED", "REJECTED", "EXPIRED", "INVALIDATED")
fun statusLabel(status: String): String = when (status) {
    "PAYMENT_METHOD_REQUIRED" -> "카드 선택 필요"
    "PENDING" -> "승인 대기 중"
    "APPROVED" -> "승인 전달됨"
    "COMPLETED" -> "결제 완료"
    "REJECTED" -> "요청 거절됨"
    "EXPIRED" -> "기간 만료"
    "INVALIDATED" -> "취소됨"
    "LOCKED" -> "잠김"
    "ACTIVE" -> "사용 중"
    "NOT_REGISTERED" -> "등록 필요"
    "REVOKED" -> "다시 인증 필요"
    else -> "상태 확인 필요"
}

/** Display labels only: request filters and financial state retain the original backend values. */
val orderStatuses = listOf("CREATED", "CONFIRMED", "FULFILLING", "SHIPPED", "DELIVERED", "CANCELLED", "FAILED")
fun orderStatusLabel(status: String): String = when (status) {
    "CREATED" -> "주문 등록됨"
    "CONFIRMED" -> "주문 확정"
    "FULFILLING" -> "배송 준비 중"
    "SHIPPED" -> "배송 중"
    "DELIVERED" -> "배송 완료"
    "CANCELLED" -> "주문 취소됨"
    "FAILED" -> "주문 실패"
    else -> "상태 확인 필요"
}

fun cardStatusLabel(status: String): String = when (status) {
    "ACTIVE" -> "사용 중"
    "REVOKED" -> "사용 중지됨"
    else -> "상태 확인 필요"
}

fun connectionScopeLabel(scope: String): String = when (scope) {
    "catalog:read" -> "상품 검색·가격·옵션 조회"
    "cart:read" -> "장바구니 조회"
    "cart:write" -> "장바구니 추가·변경·삭제"
    "purchase:handoff" -> "결제 승인 요청·상태 조회"
    else -> "권한 확인 필요"
}
