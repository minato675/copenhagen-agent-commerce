package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** Optional display enrichment runs outside checkout loading and authorization. */
class ProductImages(
    scope: CoroutineScope,
    private val pay: (suspend (String) -> JsonElement)? = null,
    private val core: suspend (String, String, JsonElement?, Map<String, String>) -> JsonElement
) {
    private companion object {
        const val SEARCH_PAGE_SIZE = 20 // Core's maximum public page size.
        const val MAX_SEARCH_RESULTS = 100 // Merchant catalog's bounded result set.
        const val MAX_ALL_MERCHANT_RESULTS = 1_000
    }
    private sealed interface ImageKey
    private data class Key(val merchantId: String?, val name: String) : ImageKey
    private data class ProductKey(val merchantId: String, val productId: String, val variantId: String?) : ImageKey
    private val work = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + work)
    private val permits = Semaphore(4)
    private val metadataPermits = Semaphore(2)
    private val cached = linkedMapOf<ImageKey, String?>()
    private val pending = mutableMapOf<ImageKey, Deferred<String?>>()
    private val pendingDetails = mutableMapOf<String, Deferred<Approval?>>()

    fun enrich(approval: Approval, representativeOnly: Boolean = false, onReady: (Approval) -> Unit) {
        val needsDetail = approval.displaySummary.isEmpty() && pay != null && validId(approval.id)
        // Older flat approvals have no merchant ID. Never guess their merchant.
        if (!needsDetail && approval.merchants.none { it.merchantId.isNotBlank() && it.summary.any { row -> row.imageUrl.isNullOrBlank() } }) return
        scope.launch {
            val source = if (needsDetail) detail(approval) else approval
            if (representativeOnly) {
                val enriched = representative(source)
                ensureActive()
                if (enriched != approval) onReady(enriched)
                return@launch
            }
            val targets = source.merchants.flatMap { merchant -> merchant.summary
                .filter { it.imageUrl.isNullOrBlank() && merchant.merchantId.isNotBlank() && it.name.isNotBlank() }
                .map { Key(merchant.merchantId, it.name) } }.distinct().take(24).toSet()
            val merchants = source.merchants.map { merchant ->
                merchant.copy(summary = coroutineScope {
                    merchant.summary.map { item -> async {
                        val key = Key(merchant.merchantId, item.name)
                        if (key !in targets || !item.imageUrl.isNullOrBlank()) item
                        else item.copy(imageUrl = lookup(key))
                    } }.awaitAll()
                })
            }
            ensureActive()
            val enriched = source.copy(merchants = merchants)
            if (enriched != approval) onReady(enriched)
        }
    }

    /** A list card needs one image, with a small sequential fallback when its first item has none. */
    suspend fun representative(approval: Approval): Approval {
        currentCoroutineContext().ensureActive()
        if (approval.displaySummary.any { !it.imageUrl.isNullOrBlank() }) return approval
        val candidates = approval.merchants.withIndex().asSequence()
            .filter { it.value.merchantId.isNotBlank() }
            .flatMap { (merchantIndex, merchant) -> merchant.summary.withIndex().asSequence()
                .filter { it.value.name.isNotBlank() && it.value.imageUrl.isNullOrBlank() }
                .map { (itemIndex, item) -> Triple(merchantIndex, itemIndex, Key(merchant.merchantId, item.name)) }
            }.distinctBy { it.third }.take(3)
        for ((merchantIndex, itemIndex, key) in candidates) {
            val image = lookup(key)
            currentCoroutineContext().ensureActive()
            if (image == null) continue
            return approval.copy(merchants = approval.merchants.mapIndexed { index, value ->
                if (index != merchantIndex) value else value.copy(summary = value.summary.mapIndexed { row, summary ->
                    if (row == itemIndex) summary.copy(imageUrl = image) else summary
                })
            })
        }
        return approval
    }

    /** V2 snapshots carry images; old snapshots can resolve one exact product without a catalog search. */
    fun enrich(order: Order, representativeOnly: Boolean = false, allowNameFallback: Boolean = false,
        onReady: (Order) -> Unit) {
        if (representativeOnly && order.items.any { !it.imageUrl.isNullOrBlank() }) return
        val candidates = order.items.filter { it.imageUrl.isNullOrBlank() }
            .mapNotNull { it.imageKey(allowNameFallback) }.distinct()
        val targets = (if (representativeOnly) candidates.take(1)
            else candidates.filterIsInstance<ProductKey>() + candidates.filterIsInstance<Key>().take(24)).toSet()
        if (targets.isEmpty()) return
        scope.launch {
            val items = coroutineScope { order.items.map { item -> async {
                val key = item.imageKey(allowNameFallback)
                if (key !in targets || !item.imageUrl.isNullOrBlank()) item
                else item.copy(imageUrl = when (key) {
                    is ProductKey -> lookup(key)
                    is Key -> lookup(key)
                    null -> null
                })
            } }.awaitAll() }
            ensureActive()
            val enriched = order.copy(items = items)
            if (enriched != order) onReady(enriched)
        }
    }

    private fun ItemSummary.imageKey(allowNameFallback: Boolean): ImageKey? {
        val merchant = merchantId?.takeIf { it.isNotBlank() }
        val product = merchantProductId?.takeIf { it.isNotBlank() }
        if (merchant != null && product != null) {
            // Http encodes raw path segments once. Reject path separators and traversal segments.
            if (!validDecorationProductId(product)) return null
            return ProductKey(merchant, product, merchantVariantId?.takeIf { it.isNotBlank() })
        }
        // Only explicitly enabled v1 compatibility may search by name. Never guess missing v2 IDs.
        return if (allowNameFallback && merchant == null && product == null && name.isNotBlank()) Key(null, name) else null
    }

    private suspend fun lookup(key: ProductKey): String? {
        if (cached.containsKey(key)) return cached[key]
        return shared(pending, key) {
            val detail = permits.withPermit { optional {
                val query = buildMap {
                    put("merchantId", key.merchantId)
                    key.variantId?.let { put("optionId", it) }
                }
                core("GET", "/products/${key.productId}", null, query)
                    .jsonObject.getValue("data").read<ProductDetailResult>()
            } } ?: return@shared null
            currentCoroutineContext().ensureActive()
            if (detail.request.productId != key.productId || detail.request.merchantId != key.merchantId ||
                detail.request.optionId?.takeIf { it.isNotBlank() } != key.variantId) return@shared null
            val product = detail.product
            if (detail.status == "NOT_FOUND" && product == null) {
                remember(key, null)
                return@shared null
            }
            if (detail.status != "FOUND" || product == null || product.productId != key.productId ||
                product.offer.merchantId != key.merchantId) return@shared null
            if (key.variantId != null && (product.selectionStatus != "VALID" ||
                product.selectedOptionId != key.variantId || product.variants.none { it.optionId == key.variantId })) return@shared null
            product.imageUrl?.takeIf { it.isNotBlank() }.also { remember(key, it) }
        }
    }

    private fun remember(key: ImageKey, imageUrl: String?) {
        if (cached.size >= 128 && !cached.containsKey(key)) cached.remove(cached.keys.first())
        cached[key] = imageUrl
    }

    /** Historical purchases can reference approvals absent from Pay's filtered list. */
    suspend fun approvalSnapshot(id: String): Approval? {
        currentCoroutineContext().ensureActive()
        if (pay == null || !validId(id)) return null
        return readDetail(id)
    }

    fun approvalSnapshot(id: String, onReady: (Approval) -> Unit) {
        if (pay == null || !validId(id)) return
        scope.launch {
            val approval = approvalSnapshot(id)
            ensureActive()
            if (approval != null) onReady(approval)
        }
    }

    private suspend fun readDetail(id: String): Approval? {
        return shared(pendingDetails, id) {
            metadataPermits.withPermit { optional {
                requireNotNull(pay).invoke("/payment-approvals/$id").read<Approval>().also {
                    it.requireDetail()
                    require(it.id == id)
                }
            } }
        }
    }

    private suspend fun detail(row: Approval): Approval {
        val detail = readDetail(row.id)
        val detailCount = detail?.merchants?.size?.takeIf { it > 0 } ?: detail?.merchantCount
        val rowCount = row.merchants.size.takeIf { it > 0 } ?: row.merchantCount
        if (detail == null || detail.id != row.id || detail.amountMinor != row.amountMinor || detail.currency != row.currency ||
            detailCount != rowCount) return row
        // List status, amount, expiry and payment state stay exactly as received by the owning feature.
        return row.copy(summary = detail.summary, merchants = detail.merchants)
    }

    fun cartSnapshot(onReady: (CartView) -> Unit) {
        scope.launch {
            val cart = permits.withPermit { optional { core("GET", "/cart", null, emptyMap()).read<CartView>() } }
            ensureActive()
            if (cart != null) onReady(cart)
        }
    }

    private suspend fun lookup(key: Key): String? {
        if (key.name.isBlank()) return null
        if (cached.containsKey(key)) return cached[key]
        return shared(pending, key) {
            val search = searchAllPages(key) ?: return@shared null
            currentCoroutineContext().ensureActive()
            val image = if (key.merchantId == null) search.exactOrderImage(key.name)
                else search.exactImage(key.merchantId, key.name)
            // Cache complete misses too, but allow transient failures to recover next time.
            if (search.status in setOf("OK", "EMPTY") && search.failedMerchants.isEmpty()) {
                remember(key, image)
            }
            image
        }
    }

    /** The request owns cleanup; scrolling away must not discard another card's active read. */
    private suspend fun <K, T> shared(requests: MutableMap<K, Deferred<T>>, key: K, read: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val request = requests.getOrPut(key) {
            // Register before starting, including when the main dispatcher can execute inline.
            scope.async(start = CoroutineStart.LAZY) {
                try { read() } finally {
                    if (requests[key] === currentCoroutineContext()[Job]) requests.remove(key)
                }
            }
        }
        return request.await()
    }

    /** Catalog search matches related words, so even a full title commonly spans several pages. */
    private suspend fun searchAllPages(key: Key): ProductSearch? {
        val products = mutableListOf<CatalogProduct>()
        val productOffers = mutableSetOf<Pair<String, String>>()
        var first: ProductSearch? = null
        val limit = if (key.merchantId == null) MAX_ALL_MERCHANT_RESULTS else MAX_SEARCH_RESULTS
        for (page in 1..(limit / SEARCH_PAGE_SIZE)) {
            val body = JsonObject(ProductFilters(query = key.name, merchantId = key.merchantId.orEmpty()).body(page) +
                ("pageSize" to JsonPrimitive(SEARCH_PAGE_SIZE)))
            // Queueing behind other images does not consume this HTTP request's timeout.
            val result = permits.withPermit { optional {
                core("POST", "/products/search", body, emptyMap())
                    .jsonObject.getValue("data").read<ProductSearch>()
            } } ?: return null
            if (result.failedMerchants.isNotEmpty() || result.status !in setOf("OK", "EMPTY") ||
                result.total !in 0..limit) return null
            if (first == null) first = result
            if (result.total != first.total || result.merchants != first.merchants) return null
            if (key.merchantId == null && result.total > MAX_SEARCH_RESULTS * result.merchants.size.coerceIn(1, 10)) return null
            val remaining = result.total - products.size
            if (remaining < 0 || result.products.size != minOf(SEARCH_PAGE_SIZE, remaining)) return null
            if (result.status != if (result.products.isEmpty()) "EMPTY" else "OK") return null
            // Core returns the same product ID once per merchant, without merging offers.
            if (result.products.any { product -> product.productId.isBlank() || product.offers.isEmpty() ||
                product.offers.any { it.merchantId.isBlank() || !productOffers.add(product.productId to it.merchantId) } }) return null
            products += result.products
            if (result.hasNext != (products.size < result.total)) return null
            if (!result.hasNext) return first.copy(products = products.toList(), hasNext = false)
        }
        // Never cache a partial or changing result set as a definitive missing image.
        return null
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        withTimeoutOrNull(4_000) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null // An unavailable image must never become an unavailable purchase or approval.
    }

    fun clear() {
        work.cancelChildren()
        pending.clear(); pendingDetails.clear(); cached.clear()
    }
}

/** Only a complete, unambiguous exact match from the original merchant can decorate a snapshot. */
internal fun ProductSearch.exactImage(merchantId: String, name: String): String? {
    if (merchantId.isBlank() || name.isBlank() || status != "OK" || hasNext || failedMerchants.isNotEmpty() || total != products.size) return null
    val product = products.filter { it.name == name && it.offers.any { offer -> offer.merchantId == merchantId } }.singleOrNull()
        ?: return null
    return product.imageUrl?.takeIf { it.isNotBlank() }
}

/** A name-only order may use an image only when the full catalog has one exact product match. */
internal fun ProductSearch.exactOrderImage(name: String): String? {
    if (name.isBlank() || status != "OK" || hasNext || failedMerchants.isNotEmpty() || total != products.size) return null
    val matches = products.filter { it.name == name }
    if (matches.isEmpty() || matches.any { it.productId.isBlank() || it.offers.isEmpty() || it.imageUrl.isNullOrBlank() } ||
        matches.map { it.productId }.distinct().size != 1) return null
    // Multiple merchants can sell the same catalog product; their image must agree too.
    return matches.map { it.imageUrl }.distinct().singleOrNull()
}
