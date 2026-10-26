package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement

/** Independent read models keep history browsing out of the active approval/address operation. */
class PurchaseHistory(private val core: suspend (String) -> JsonElement, private val pay: suspend (String) -> JsonElement,
    private val images: ProductImages? = null) {
    var entries by mutableStateOf<List<PurchaseHistoryEntry>?>(null); private set
    var detail by mutableStateOf<PurchaseHistoryEntry?>(null); private set
    var listError by mutableStateOf<String?>(null); private set
    var detailError by mutableStateOf<String?>(null); private set
    var listNotice by mutableStateOf<String?>(null); private set
    var detailNotice by mutableStateOf<String?>(null); private set
    var filter by mutableStateOf(PurchaseHistoryFilter.ALL)
    var imageRevision by mutableStateOf(0L); private set
    var searchLoading by mutableStateOf(false); private set
    var searchNotice by mutableStateOf<String?>(null); private set
    private var searchRevision = 0L
    private val thumbnailLoads = mutableSetOf<Pair<Long, String>>()
    private var listRevision = 0L
    private var detailRevision = 0L

    suspend fun load() {
        val revision = ++listRevision
        listError = null; listNotice = null
        try {
            val rows = core("/purchase-requests").read<List<Purchase>>()
            rows.forEach { it.requireHistoryRecord() }
            check(rows.map { it.purchaseRequestId }.distinct().size == rows.size)
            if (revision != listRevision) return
            val cached = entries.orEmpty().associateBy { it.id }
            entries = rows.map { value -> withMetadata(value, cached[value.purchaseRequestId]) }
            // Core owns the result and amounts. Optional Pay/catalog reads must not hold
            // the app's navigation/loading lock or enumerate the off-screen history.
            imageRevision++
        } catch (failure: Exception) {
            rethrowSessionOrCancellation(failure)
            if (revision == listRevision) listError = failure.userMessage()
        }
    }

    suspend fun open(id: String) {
        require(validId(id))
        val revision = ++detailRevision
        if (detail?.id != id) detail = entries?.firstOrNull { it.id == id }
        detailError = null; detailNotice = null
        try {
            val purchase = core("/purchase-requests/$id").read<Purchase>()
            check(purchase.purchaseRequestId == id) { "다른 구매 요청의 응답을 받았어요. 다시 확인해 주세요." }
            purchase.requireHistoryRecord()
            if (revision != detailRevision) return
            var record = withMetadata(purchase, detail ?: entries?.firstOrNull { it.id == id })
            detail = record
            updateList(record)
            for (approvalId in record.approvalIds) {
                if (revision != detailRevision) return
                try {
                    val approval = pay("/payment-approvals/$approvalId").read<Approval>()
                    approval.requireDetail()
                    check(approval.id == approvalId && approval.matchesCheckouts(purchase.merchantCheckouts.filter { it.approvalId == approvalId }))
                    if (revision != detailRevision) return
                    record = record.copy(approvals = record.approvals + (approvalId to approval.withKnownImages(record.approvals[approvalId])))
                    detail = record
                    updateList(record)
                    enrich(record)
                } catch (failure: Exception) {
                    rethrowSessionOrCancellation(failure)
                    if (revision == detailRevision) detailNotice = "일부 상점·상품 정보를 확인하지 못했어요. 표시된 결제 결과와 금액은 구매 기록 기준이에요."
                }
            }
        } catch (failure: Exception) {
            rethrowSessionOrCancellation(failure)
            if (revision == detailRevision) detailError = failure.userMessage()
        }
    }

    private fun updateList(record: PurchaseHistoryEntry) {
        entries = entries?.map { if (it.id == record.id) record else it }
    }
    /** Called only by composed cards; one representative image is enough for a list row. */
    suspend fun ensureThumbnail(id: String) {
        val resolver = images ?: return
        val revision = listRevision
        val record = entries?.firstOrNull { it.id == id } ?: return
        if (record.hasImage()) return
        val key = revision to id
        if (!thumbnailLoads.add(key)) return
        try {
            for (approvalId in record.approvalIds) {
                val approval = metadata(record, approvalId, revision) ?: continue
                val current = current(record, revision) ?: return
                if (current.hasImage()) return
                if (approval.merchants.none { merchant -> merchant.summary.any { it.name.isNotBlank() } }) continue
                val decorated = resolver.representative(approval)
                val latest = current(record, revision) ?: return
                if (latest.approvals[approvalId] == approval && decorated != approval)
                    updateList(latest.copy(approvals = latest.approvals + (approvalId to decorated)))
                if (decorated.displaySummary.any { !it.imageUrl.isNullOrBlank() }) return
            }
        } finally { thumbnailLoads.remove(key) }
    }

    /** Product-name search also needs off-screen names, but never off-screen images. */
    suspend fun prepareSearch(query: String, selectedFilter: PurchaseHistoryFilter) {
        val search = ++searchRevision
        val revision = listRevision
        searchNotice = null
        val records = entries.orEmpty().filter { it.matches(selectedFilter, "") }
        searchLoading = query.isNotBlank() && records.any { record ->
            record.approvalIds.any { it !in record.approvals }
        }
        if (!searchLoading) return
        try {
            delay(250)
            coroutineScope {
                // Two workers bound the queue as well as concurrent HTTP calls.
                List(2) { worker -> launch {
                    records.filterIndexed { index, _ -> index % 2 == worker }.forEach { record ->
                        for (id in record.approvalIds) {
                            ensureActive()
                            if (search != searchRevision || revision != listRevision) return@launch
                            if (metadata(record, id, revision) == null && search == searchRevision && revision == listRevision)
                                searchNotice = "일부 상품·판매처 정보를 불러오지 못해 검색 결과가 빠질 수 있어요. 다시 불러와 주세요."
                        }
                    }
                } }.joinAll()
            }
        } finally {
            if (search == searchRevision) searchLoading = false
        }
    }

    private fun current(record: PurchaseHistoryEntry, revision: Long): PurchaseHistoryEntry? =
        if (revision != listRevision) null else entries?.firstOrNull { it.id == record.id && it.purchase == record.purchase }

    private suspend fun metadata(record: PurchaseHistoryEntry, id: String, revision: Long): Approval? {
        val before = current(record, revision) ?: return null
        before.approvals[id]?.let { return it }
        val approval = images?.approvalSnapshot(id)
        val latest = current(record, revision) ?: return null
        if (approval == null || !approval.matchesCheckouts(record.checkouts.filter { it.approvalId == id })) {
            listNotice = "일부 상품·판매처 정보를 불러오지 못했어요. 구매 금액과 결과는 확인할 수 있어요."
            return null
        }
        // Publish names before the much slower catalog lookup. Another reader may
        // already have supplied a richer snapshot while this request was in flight.
        latest.approvals[id]?.let { return it }
        updateList(latest.copy(approvals = latest.approvals + (id to approval)))
        return approval
    }

    private fun withMetadata(purchase: Purchase, cached: PurchaseHistoryEntry?) = PurchaseHistoryEntry(purchase,
        cached?.approvals.orEmpty().filter { (id, approval) ->
            approval.matchesCheckouts(purchase.merchantCheckouts.filter { it.approvalId == id })
        })

    private fun PurchaseHistoryEntry.hasImage() = checkouts.flatMap(::items).any { !it.imageUrl.isNullOrBlank() }

    /** Keep already resolved images only when the exact merchant and item snapshot still agree. */
    private fun Approval.withKnownImages(previous: Approval?): Approval {
        if (previous == null || id != previous.id) return this
        return copy(merchants = merchants.map { merchant ->
            val old = previous.merchants.singleOrNull { it.merchantId == merchant.merchantId }
            merchant.copy(summary = merchant.summary.map { item ->
                if (!item.imageUrl.isNullOrBlank()) item else item.copy(imageUrl = old?.summary?.firstOrNull {
                    it.copy(imageUrl = null) == item.copy(imageUrl = null)
                }?.imageUrl)
            })
        })
    }
    private fun enrich(record: PurchaseHistoryEntry) {
        val listRead = listRevision
        val detailRead = detailRevision
        record.approvals.forEach { (id, approval) -> images?.enrich(approval) imageReady@ { enriched ->
            if (!enriched.matchesCheckouts(record.checkouts.filter { it.approvalId == id })) return@imageReady
            fun update(value: PurchaseHistoryEntry) = if (value.id == record.id && value.approvals[id] == approval)
                value.copy(approvals = value.approvals + (id to enriched)) else value
            if (listRead == listRevision) entries = entries?.map(::update)
            if (detailRead == detailRevision) detail = detail?.let(::update)
        } }
    }
    private fun rethrowSessionOrCancellation(failure: Exception) {
        if (failure is CancellationException || failure is SessionExpired) throw failure
    }
    fun clear() {
        listRevision++; detailRevision++; searchRevision++; imageRevision++
        thumbnailLoads.clear(); searchLoading = false; searchNotice = null
        entries = null; detail = null; listError = null; detailError = null; listNotice = null; detailNotice = null
        filter = PurchaseHistoryFilter.ALL
    }
}
