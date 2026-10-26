package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.serialization.json.*

/** Address reads use Core v1; order reads prefer the self-contained v2 snapshot. */
typealias AccountRequest = suspend (String, String, JsonElement?, Map<String, String>) -> JsonElement

class Account(private val request: AccountRequest, private val images: ProductImages? = null,
    private val orderReads: OrderHistoryReads = OrderHistoryReads({ path, query -> request("GET", path, null, query) })) {
    constructor(api: Api, images: ProductImages? = null) : this(api::core, images, OrderHistoryReads(api::orderHistory))
    private suspend fun core(method: String, path: String, body: JsonElement? = null,
        query: Map<String, String> = emptyMap()) = request(method, path, body, query)
    private var orderListRevision = 0L
    private var orderDetailRevision = 0L
    private var pendingOrderPage: Pair<Long, String>? = null
    private var legacyOrderIds = emptySet<String>()
    var orderImageRevision by mutableLongStateOf(0L); private set
    var addresses by mutableStateOf<List<Address>?>(null); private set
    var addressId: String? = null
    var form by mutableStateOf(AddressForm())
    var makeDefault by mutableStateOf(false)
    var orders by mutableStateOf<Items<Order>?>(null); private set
    private var loadedOrderStatus: String? = null
    var order by mutableStateOf<Order?>(null); private set
    var orderStatus by mutableStateOf("")
    var profile by mutableStateOf<Profile?>(null); private set
    suspend fun loadAddresses() { addresses = core("GET", "/addresses").read<Items<Address>>().items }
    fun newAddress() {
        require(addresses.orEmpty().size < 20) { "배송지는 20개까지 등록할 수 있어요." }
        addressId = null; form = AddressForm(); makeDefault = false
    }
    suspend fun editAddress(id: String) {
        val value = core("GET", "/addresses/$id").read<Address>()
        addressId = value.id
        form = AddressForm(value.recipient, value.phone, value.postalCode, value.address, value.detail, value.memo)
        makeDefault = value.isDefault
    }
    suspend fun saveAddress() {
        require(form.valid) { "배송지 정보를 확인해 주세요." }
        val id = addressId
        if (id == null) {
            val response = core("POST", "/addresses", json.encodeToJsonElement(form)).jsonObject
            addressId = response.getValue("addressId").jsonPrimitive.content
        } else core("PUT", "/addresses/$id", json.encodeToJsonElement(form))
        if (makeDefault) core("PATCH", "/addresses/$addressId/default")
        loadAddresses()
    }
    suspend fun defaultAddress(id: String) { core("PATCH", "/addresses/$id/default"); loadAddresses() }
    suspend fun deleteAddress() { core("DELETE", "/addresses/${requireNotNull(addressId)}"); loadAddresses() }
    suspend fun loadOrders(more: Boolean = false) {
        val status = orderStatus
        require(status.isBlank() || status in orderStatuses) { "주문 상태를 다시 선택해 주세요." }
        val previous = orders
        if (more && (previous?.nextCursor == null || loadedOrderStatus != status)) return
        val revision = if (more) orderListRevision else ++orderListRevision
        val pageKey = if (more) revision to previous!!.nextCursor!! else null
        if (pageKey != null && pendingOrderPage == pageKey) return
        pendingOrderPage = pageKey
        // Keep the current list during a read of the same filter; a different filter needs its own result.
        if (!more && loadedOrderStatus != status) orders = null
        try {
            val read = orderReads.list(buildMap {
                put("size", "20")
                if (more) put("cursor", previous!!.nextCursor!!)
                if (status.isNotBlank()) put("status", status)
            })
            val response = read.value
            response.items.forEach(::requireOrder)
            check(response.items.map { it.id }.distinct().size == response.items.size)
            if (revision != orderListRevision || status != orderStatus) return
            // Use current rows so a thumbnail that arrived during pagination is retained.
            val retainedIds = if (more) orders!!.items.mapTo(mutableSetOf()) { it.id } else emptySet()
            orders = if (more) response.copy(items = (orders!!.items + response.items).distinctBy { it.id }) else response
            loadedOrderStatus = status
            val newLegacyIds = if (read.legacy) response.items.filterNot { it.id in retainedIds }.map { it.id }.toSet() else emptySet()
            legacyOrderIds = if (more) legacyOrderIds + newLegacyIds else newLegacyIds
        } finally {
            if (pendingOrderPage == pageKey) pendingOrderPage = null
            if (!more && revision == orderListRevision) orderImageRevision++
        }
    }
    /** Lazy list cards request at most their representative missing image, only when composed. */
    fun ensureOrderThumbnail(id: String) {
        val original = orders?.items?.firstOrNull { it.id == id } ?: return
        val revision = orderListRevision
        val status = orderStatus
        images?.enrich(original, representativeOnly = true, allowNameFallback = id in legacyOrderIds) { enriched ->
            if (revision == orderListRevision && status == orderStatus) orders = orders?.let { page ->
                page.copy(items = page.items.map { if (it == original) enriched else it })
            }
        }
    }
    suspend fun openOrder(id: String) {
        require(validId(id))
        val revision = ++orderDetailRevision
        order = order?.takeIf { it.id == id } ?: orders?.items?.firstOrNull { it.id == id }
        val read = orderReads.detail(id)
        val original = read.value
        requireOrder(original)
        check(original.id == id) { "주문 정보를 다시 확인해 주세요." }
        if (revision != orderDetailRevision) return
        order = original
        images?.enrich(original, allowNameFallback = read.legacy) { enriched ->
            if (revision == orderDetailRevision && order == original) order = enriched
        }
    }
    suspend fun loadProfile() { profile = core("GET", "/users/me").read<Profile>() }
    private fun requireOrder(value: Order) {
        check(validId(value.id) && value.amountMinor >= 0 && value.currency.isNotBlank() &&
            value.items.all { it.quantity > 0 }) { "주문 정보를 다시 확인해 주세요." }
    }
    fun clear() {
        orderListRevision++; orderDetailRevision++
        pendingOrderPage = null; legacyOrderIds = emptySet(); orderImageRevision++; orderReads.clear()
        addresses = null; addressId = null; form = AddressForm(); makeDefault = false
        orders = null; loadedOrderStatus = null; order = null; profile = null
    }
}
