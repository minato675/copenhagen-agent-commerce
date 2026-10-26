package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** A narrow transport seam keeps approval lifecycle tests independent of Android authentication. */
typealias ApprovalRequest = suspend (String, String, JsonElement?, String?, Map<String, String>, Boolean) -> JsonElement
private class ApprovalTransport(private val request: ApprovalRequest) {
    suspend fun pay(method: String, path: String, body: JsonElement? = null, key: String? = null,
        query: Map<String, String> = emptyMap(), deviceHeader: Boolean = false) =
        request(method, path, body, key, query, deviceHeader)
}

/** Presentation may leave; an unresolved server operation keeps its identity until a real result. */
class Approvals private constructor(
    private val api: ApprovalTransport,
    recoveredId: String?,
    private val images: ProductImages?,
    private val persistUnresolved: (String?) -> Unit
) {
    constructor(api: Api, recoveredId: String? = null, images: ProductImages? = null, persistUnresolved: (String?) -> Unit = {}) : this(
        ApprovalTransport { method, path, body, key, query, device -> api.pay(method, path, body, key, query, device) },
        recoveredId, images, persistUnresolved
    )
    constructor(request: ApprovalRequest, recoveredId: String? = null, images: ProductImages? = null, persistUnresolved: (String?) -> Unit = {}) : this(
        ApprovalTransport(request), recoveredId, images, persistUnresolved
    )

    var inbox by mutableStateOf<List<Approval>?>(null); private set
    var current by mutableStateOf<Approval?>(null); private set
    var result by mutableStateOf<String?>(null); private set
    var uncertain by mutableStateOf(recoveredId != null); private set
    var submitting by mutableStateOf(false); private set
    var operationStartedAt by mutableStateOf<Long?>(null); private set
    var history by mutableStateOf<List<Approval>?>(null); private set
    var historyRefreshing by mutableStateOf(false); private set
    var historyStatus by mutableStateOf("APPROVED")
    var pendingId: String? = recoveredId
    private var operationId by mutableStateOf(recoveredId)
    val unresolvedId: String? get() = operationId.takeIf { uncertain || submitting }
    val canAuthorize get() = !uncertain && !submitting
    private var auth: JsonObject? = null
    private var authFingerprint: String? = null
    private var key = ""
    private var attemptKind: String? = null
    private var submittedReview: Approval? = null
    private var operationRevision = 0
    private var detailRevision = 0
    private var inboxRevision = 0
    private var historyRevision = 0
    private var reconciled = false

    suspend fun loadInbox() {
        val revision = ++inboxRevision
        val waiting = api.pay("GET", "/payment-approvals", query = mapOf("status" to "PENDING")).read<Items<Approval>>()
        val cardRequired = api.pay("GET", "/payment-approvals", query = mapOf("status" to "PAYMENT_METHOD_REQUIRED")).read<Items<Approval>>()
        if (revision == inboxRevision) {
            inbox = (waiting.items + cardRequired.items).distinctBy { it.id }.sortedBy { it.expiresAt }
        }
    }
    suspend fun loadHistory() {
        val revision = ++historyRevision
        val filter = historyStatus
        historyRefreshing = true
        try {
            val rows = api.pay("GET", "/payment-approvals", query = mapOf("status" to filter)).read<Items<Approval>>().items
            if (revision == historyRevision && historyStatus == filter) {
                history = rows
            }
        } finally { if (revision == historyRevision) historyRefreshing = false }
    }
    /** Only displayed list cards request optional metadata and a representative image. */
    fun ensureThumbnail(id: String) {
        val row = inbox?.firstOrNull { it.id == id } ?: history?.firstOrNull { it.id == id } ?: return
        if (row.displaySummary.any { !it.imageUrl.isNullOrBlank() }) return
        val inboxRead = inboxRevision
        val historyRead = historyRevision
        images?.enrich(row, representativeOnly = true) { enriched ->
            if (inboxRead == inboxRevision) inbox = inbox?.map { if (it == row) enriched else it }
            if (historyRead == historyRevision) history = history?.map { if (it == row) enriched else it }
        }
    }
    /** The actual receipt is already authoritative; its matching history row need not wait for a read. */
    fun prepareReceiptHistory(): Approval? {
        if (uncertain || submitting) return null
        val receipt = current?.let { it.copy(status = result ?: it.status) } ?: return null
        if (receipt.status !in approvalStatuses) return null
        historyRevision++ // A prior filter/read cannot erase the receipt we are leaving.
        historyRefreshing = false
        historyStatus = receipt.status
        val matching = history.orEmpty().filter { it.status == receipt.status }.distinctBy { it.id }
        history = if (matching.any { it.id == receipt.id }) matching.map { if (it.id == receipt.id) receipt else it }
            else listOf(receipt) + matching
        return receipt
    }
    suspend fun open(id: String) {
        check(unresolvedId == null || unresolvedId == id) { "이전 승인 결과를 먼저 확인해 주세요." }
        val revision = ++detailRevision
        val sameObject = current?.id == id
        if (!sameObject && unresolvedId == null) { clearAttempt(); result = null; current = null }
        pendingId = id
        val found = api.pay("GET", "/payment-approvals/$id", deviceHeader = true).read<Approval>()
        check(found.id == id) { "승인 요청 응답을 확인할 수 없어요." }
        found.requireDetail()
        if (revision == detailRevision) {
            current = found
            images?.enrich(found) { enriched ->
                if (revision == detailRevision && current == found) current = enriched
            }
            if (result != null || !found.actionable) result = found.status
            if (!submitting && unresolvedId == id && found.status in approvalStatuses && !found.actionable) finishAttempt()
        }
    }
    suspend fun selectCard(id: String) {
        check(canAuthorize) { "이전 승인 결과를 먼저 확인해 주세요." }
        val approval = requireNotNull(current)
        require(approval.actionable && !approval.expired()) { "승인 요청이 만료되었어요." }
        api.pay("PATCH", "/payment-approvals/${approval.id}/payment-method",
            buildJsonObject { put("paymentMethodId", id) }, newKey())
        clearAttempt()
        open(approval.id)
    }
    suspend fun approve(body: JsonObject): String {
        check(!submitting) { "승인 결과를 확인하고 있어요." }
        val approval = requireNotNull(current)
        require(approval.status == "PENDING" && !approval.expired()) { "승인 요청 상태를 다시 확인해 주세요." }
        require(approval.currency == "KRW") { "원화 승인 요청만 처리할 수 있어요." }
        require(!approval.selectedPaymentMethodId.isNullOrBlank()) { "결제 카드를 선택해 주세요." }
        if (uncertain) {
            check(reconciled && attemptKind == "approve" && key.isNotBlank() && fingerprint(body) == authFingerprint &&
                submittedReview?.sameReviewAs(approval) == true) { "이전 승인 결과를 먼저 확인해 주세요." }
        } else {
            key = newKey(); authFingerprint = fingerprint(body); submittedReview = approval
            attemptKind = "approve"
        }
        auth = body
        return submit(approval, "approve", body)
    }
    suspend fun retry(): String {
        check(!submitting && uncertain && auth != null) { "처리 상태를 먼저 확인해 주세요." }
        val status = reconcile()
        if (status != "PENDING") return status
        check(canRetry) { "처리 상태를 확인해 주세요. 새 승인은 보내지 않았어요." }
        return approve(requireNotNull(auth))
    }
    val rejecting get() = submitting && attemptKind == "reject"
    val canRetry get() = uncertain && !submitting && attemptKind == "approve" && auth != null && key.isNotBlank()
    suspend fun reconcile(): String {
        check(!submitting) { "승인 결과를 확인하고 있어요." }
        val id = unresolvedId ?: requireNotNull(pendingId)
        open(id)
        val approval = requireNotNull(current)
        check(approval.id == id) { "승인 결과를 다시 확인해 주세요." }
        val status = approval.status
        check(status in approvalStatuses) { "승인 처리 상태를 확인할 수 없어요. 다시 확인해 주세요." }
        result = status
        if (status !in listOf("PENDING", "PAYMENT_METHOD_REQUIRED")) finishAttempt()
        else if (uncertain) {
            // A still-pending GET alone never proves the original mutation was not accepted.
            // Only an identical retained request can use its original idempotency key.
            reconciled = true
        }
        return status
    }
    suspend fun reject(): String {
        check(canAuthorize) { "이전 승인 결과를 먼저 확인해 주세요." }
        val approval = requireNotNull(current)
        require(approval.actionable && !approval.expired()) { "승인 요청 상태를 다시 확인해 주세요." }
        key = newKey(); attemptKind = "reject"; submittedReview = approval
        return submit(approval, "reject", buildJsonObject { put("reason", "USER_REJECTED") })
    }
    private suspend fun submit(approval: Approval, action: String, body: JsonObject): String {
        val resolvingUnknown = uncertain
        val revision = ++operationRevision
        operationId = approval.id; pendingId = approval.id
        uncertain = true; submitting = true; reconciled = false
        operationStartedAt = System.currentTimeMillis()
        detailRevision++ // Reads started before dispatch cannot replace the operation's result.
        try {
            persistUnresolved(approval.id)
            val response = api.pay("POST", "/payment-approvals/${approval.id}/$action", body, key).read<ApprovalResult>()
            check(response.approvalId == approval.id) { "승인 결과를 확인할 수 없어요." }
            check(response.status in approvalStatuses) { "승인 처리 상태를 확인할 수 없어요." }
            if (revision != operationRevision) throw CancellationException("Session changed")
            detailRevision++
            result = response.status
            current = current?.takeIf { it.id == approval.id }?.copy(status = response.status)
            if (response.status !in listOf("PENDING", "PAYMENT_METHOD_REQUIRED")) finishAttempt()
            return response.status
        } catch (failure: ApiError) {
            val rejectedPin = failure.status == 429 && failure.code.contains("PIN")
            val definitiveRejection = (failure.status in 400..499 && failure.status !in setOf(408, 409, 425, 429)) || rejectedPin
            if (revision == operationRevision && !resolvingUnknown && definitiveRejection) {
                finishAttempt() // A definitive validation/authentication rejection is safe to correct.
            }
            // A rejected replay cannot prove that the earlier, unobserved request was not accepted.
            throw failure
        } finally {
            if (revision == operationRevision) submitting = false
        }
    }
    /** App backgrounding discards authentication material without inventing a transaction outcome. */
    fun forgetAuthentication() { auth = null }
    fun clearAttempt() {
        forgetAuthentication()
        if (!uncertain && !submitting) finishAttempt()
    }
    private fun finishAttempt() {
        persistUnresolved(null)
        auth = null; authFingerprint = null; key = ""; attemptKind = null; submittedReview = null
        uncertain = false; operationId = null; reconciled = false; operationStartedAt = null
    }
    fun close() {
        detailRevision++
        forgetAuthentication()
        if (unresolvedId != null) { pendingId = unresolvedId; return }
        clearAttempt(); current = null; pendingId = null; result = null
    }
    fun clear() {
        operationRevision++; detailRevision++; inboxRevision++; historyRevision++
        submitting = false; finishAttempt(); current = null; pendingId = null; result = null
        inbox = null; history = null; historyRefreshing = false
    }
    private fun fingerprint(body: JsonObject) = MessageDigest.getInstance("SHA-256")
        .digest(body.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
