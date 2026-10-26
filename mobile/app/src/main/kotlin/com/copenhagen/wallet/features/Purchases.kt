package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

fun interface PurchaseTransport {
    suspend fun request(method: String, path: String, body: JsonElement?, key: String?): JsonElement
}

/** Shipping selection resumes a server-owned purchase. It never authorizes its payment. */
class Purchases(
    private val transport: PurchaseTransport,
    restored: PurchaseResume? = null,
    private val images: ProductImages? = null,
    private val persist: (PurchaseResume?) -> Unit = {}
) {
    var current by mutableStateOf<Purchase?>(null); private set
    var inbox by mutableStateOf<List<Purchase>?>(null); private set
    var inboxCarts by mutableStateOf<Map<String, CartView>>(emptyMap()); private set
    var inboxError by mutableStateOf<String?>(null); private set
    var addresses by mutableStateOf<List<Address>?>(null); private set
    var cart by mutableStateOf<CartView?>(null); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var submitting by mutableStateOf(false); private set
    var operation by mutableStateOf(restored); private set
    var confirmedSelection by mutableStateOf<Address?>(null); private set
    private var generation = 0L
    private var readVersion = 0L
    private var inboxVersion = 0L
    val uncertain get() = operation != null && !submitting
    val pendingId get() = operation?.purchaseId
    val selected get() = addresses.orEmpty().find { it.id == selectedId }

    suspend fun loadInbox() {
        val owner = generation
        val read = ++inboxVersion
        try {
            val rows = transport.request("GET", "/purchase-requests", null, null).read<List<Purchase>>()
            if (owner != generation || read != inboxVersion) return
            inbox = rows.filter { validId(it.purchaseRequestId) && !it.terminal }
            inboxCarts = inboxCarts.filter { (id, cart) -> inbox.orEmpty().any {
                it.purchaseRequestId == id && it.cartId == cart.cartId && it.cartVersion == cart.version
            } }
            inboxError = null
            if (inbox.orEmpty().any { it.needsAddress }) images?.cartSnapshot { snapshot ->
                if (owner == generation && read == inboxVersion) inboxCarts = inbox.orEmpty()
                    .filter { it.needsAddress && it.cartId == snapshot.cartId && it.cartVersion == snapshot.version }
                    .associate { it.purchaseRequestId to snapshot }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (expired: SessionExpired) { throw expired }
        catch (failure: Exception) { if (owner == generation && read == inboxVersion) inboxError = failure.userMessage() }
    }

    suspend fun open(id: String) {
        require(validId(id))
        check(!submitting) { "배송지 선택 결과를 확인하고 있어요." }
        if (current?.purchaseRequestId != id) {
            current = null; addresses = null; cart = null; selectedId = null; confirmedSelection = null
        }
        val owner = generation
        val read = ++readVersion
        val value = readPurchase(id)
        if (owner != generation || read != readVersion) return
        apply(value)
        if (value.needsAddress) {
            val rows = transport.request("GET", "/addresses", null, null).read<Items<Address>>().items
            if (owner != generation || read != readVersion) return
            addresses = rows.filter { validId(it.id) }
            selectedId = operation?.takeIf { it.purchaseId == id }?.addressId
                ?: selectedId?.takeIf { chosen -> addresses.orEmpty().any { it.id == chosen } }
                ?: addresses.orEmpty().firstOrNull { it.isDefault }?.id
            // A cart is supporting context only when it is exactly this purchase's snapshot.
            val snapshot = transport.request("GET", "/cart", null, null).read<CartView>()
            if (owner != generation || read != readVersion) return
            cart = snapshot.takeIf { it.cartId == value.cartId && it.version == value.cartVersion }
            cart?.let { inboxCarts = inboxCarts + (id to it) }
        }
    }

    fun select(id: String) {
        check(!submitting && operation == null)
        require(addresses.orEmpty().any { it.id == id }) { "배송지를 다시 선택해 주세요." }
        selectedId = id
    }

    suspend fun confirm() {
        if (submitting) return
        check(operation == null) { "이전 선택 결과를 먼저 확인해 주세요." }
        val review = requireNotNull(current)
        val address = requireNotNull(selected) { "배송지를 선택해 주세요." }
        check(review.needsAddress && !review.expired()) { "배송지 선택 요청이 만료되었어요." }
        submitting = true
        val owner = generation
        ++readVersion
        try {
            val latest = readPurchase(review.purchaseRequestId)
            if (owner != generation) return
            apply(latest)
            check(latest.needsAddress && !latest.expired() && latest.cartId == review.cartId && latest.cartVersion == review.cartVersion) {
                "구매 요청이 변경되었어요. 다시 확인해 주세요."
            }
            val command = PurchaseResume(review.purchaseRequestId, address.id, review.cartVersion, newKey())
            persist(command) // Must be durable before a possibly accepted command is dispatched.
            operation = command
            send(command, address, owner, replay = false)
        } finally { if (owner == generation) submitting = false }
    }

    /** Explicit retry reads first, then replays only the exact persisted command and key. */
    suspend fun retry() {
        if (submitting) return
        val command = requireNotNull(operation)
        submitting = true
        val owner = generation
        ++readVersion
        try {
            val latest = readPurchase(command.purchaseId)
            if (owner != generation) return
            apply(latest)
            if (operation == null) return
            check(latest.needsAddress && !latest.expired() && latest.cartVersion == command.cartVersion) {
                "구매 요청 상태를 다시 확인해 주세요."
            }
            send(command, selected, owner, replay = true)
        } finally { if (owner == generation) submitting = false }
    }

    suspend fun refresh() { current?.purchaseRequestId?.let { open(it) } ?: pendingId?.let { open(it) } }

    private suspend fun readPurchase(id: String): Purchase {
        val value = transport.request("GET", "/purchase-requests/$id", null, null).read<Purchase>()
        check(value.purchaseRequestId == id && validId(value.cartId) && value.cartVersion >= 0) {
            "구매 요청을 확인할 수 없어요."
        }
        value.expired() // Validate the deadline before exposing actionable state.
        return value
    }

    private fun apply(value: Purchase) {
        current = value
        cart = cart?.takeIf { it.cartId == value.cartId && it.version == value.cartVersion }
        if (value.needsAddress) confirmedSelection = null
        if (operation?.purchaseId == value.purchaseRequestId &&
            (value.status in setOf("CREATING_CHECKOUTS", "AWAITING_APPROVAL", "COMPLETING") || value.terminal)) {
            persist(null); operation = null
        }
        inbox = inbox?.map { if (it.purchaseRequestId == value.purchaseRequestId) value else it }?.filter { !it.terminal }
        inboxCarts = inboxCarts.filter { (id, snapshot) -> inbox.orEmpty().any {
            it.purchaseRequestId == id && it.needsAddress && it.cartId == snapshot.cartId && it.cartVersion == snapshot.version
        } }
    }

    private suspend fun send(command: PurchaseResume, address: Address?, owner: Long, replay: Boolean) {
        try {
            val value = transport.request("POST", "/purchase-requests/${command.purchaseId}/resume",
                buildJsonObject { put("addressId", command.addressId); put("cartVersion", command.cartVersion) }, command.key).read<Purchase>()
            if (owner != generation) return
            check(value.purchaseRequestId == command.purchaseId && value.cartVersion == command.cartVersion &&
                value.cartId == current?.cartId) { "배송지 선택 결과를 확인할 수 없어요." }
            value.expired()
            apply(value)
            if (value.status in setOf("CREATING_CHECKOUTS", "AWAITING_APPROVAL", "COMPLETING", "COMPLETED", "PARTIALLY_COMPLETED")) {
                confirmedSelection = address
            }
        } catch (failure: Exception) {
            if (owner == generation && !replay && failure is ApiError && failure.status in 400..499 &&
                failure.code in setOf("ADDRESS_NOT_FOUND", "CART_VERSION_CONFLICT", "PURCHASE_REQUEST_EXPIRED", "INVALID_REQUEST")) {
                persist(null); operation = null
                if (failure.code == "ADDRESS_NOT_FOUND") { selectedId = null; addresses = null }
            }
            throw failure
        }
    }

    fun close() { if (!submitting) { ++readVersion; current = null; cart = null; addresses = null; selectedId = null; confirmedSelection = null } }
    fun clear() {
        generation++; readVersion++; inboxVersion++; current = null; inbox = null; inboxCarts = emptyMap(); inboxError = null
        addresses = null; cart = null; selectedId = null; submitting = false; operation = null; confirmedSelection = null
    }
}
