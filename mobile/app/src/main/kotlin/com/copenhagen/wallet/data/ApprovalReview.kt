package com.copenhagen.wallet.data

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** The visible agreement must still match after revalidation and before authorization. */
fun Approval.sameReviewAs(other: Approval): Boolean =
    id == other.id && merchantName == other.merchantName && checkoutId == other.checkoutId &&
        checkoutHash == other.checkoutHash && amountMinor == other.amountMinor && currency == other.currency &&
        selectedPaymentMethodId == other.selectedPaymentMethodId && summary.withoutImages() == other.summary.withoutImages() &&
        merchants.withoutImages() == other.merchants.withoutImages() && merchantCount == other.merchantCount

// Representative images are optional catalog decoration, never part of payment authorization.
@JvmName("itemsWithoutImages")
private fun List<ItemSummary>.withoutImages() = map { it.copy(imageUrl = null) }
@JvmName("merchantsWithoutImages")
private fun List<ApprovalMerchant>.withoutImages() = map { it.copy(summary = it.summary.withoutImages()) }

/** Pay's amountMinor is an integer minor-unit contract. Core product prices use a separate formatter. */
fun approvalMoney(amountMinor: Long, currency: String): String {
    if (currency == "KRW") return money(amountMinor)
    val code = currency.uppercase(Locale.ROOT)
    val digits = runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 }
    val format = NumberFormat.getNumberInstance(Locale.KOREA)
    if (digits == null) return "${format.format(amountMinor)} $code (최소 단위)"
    format.minimumFractionDigits = digits
    format.maximumFractionDigits = digits
    return "${format.format(BigDecimal.valueOf(amountMinor, digits))} $code"
}
