package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class ProductSort(val label: String) { RELEVANCE("관련도순"), PRICE_ASC("낮은 가격순"), PRICE_DESC("높은 가격순") }
data class ProductFilters(
    val query: String = "", val merchantId: String = "", val minPrice: String = "", val maxPrice: String = "",
    val sort: ProductSort = ProductSort.RELEVANCE
) {
    fun body(page: Int): JsonObject {
        fun price(value: String): Long? {
            if (value.isBlank()) return null
            return requireNotNull(value.trim().toLongOrNull()?.takeIf { it >= 0 }) { "가격은 0 이상의 정수로 입력해 주세요." }
        }
        val min = price(minPrice); val max = price(maxPrice)
        require(min == null || max == null || min <= max) { "최저 가격이 최고 가격보다 클 수 없어요." }
        require(page >= 1) { "페이지를 확인해 주세요." }
        return buildJsonObject {
            put("query", query.trim()); put("merchantId", merchantId)
            // 현재 공개 Catalog는 브랜드·카테고리를 제공하지 않아 비어 있을 때만 검색된다.
            put("category", ""); put("brand", "")
            min?.let { put("minPrice", it) }; max?.let { put("maxPrice", it) }
            put("sort", sort.name); put("page", page); put("pageSize", 10)
        }
    }
}
@Serializable
data class ProductOffer(val merchantId: String, val merchantName: String, val price: Long?, val currency: String, val availability: String)
@Serializable
data class CatalogProduct(
    val productId: String, val name: String, val brand: String, val category: String,
    val imageUrl: String? = null, val offers: List<ProductOffer>
)
@Serializable data class MerchantRef(val merchantId: String, val name: String)
@Serializable
data class ProductSearch(
    val status: String, val products: List<CatalogProduct>, val categories: List<String>,
    val merchants: List<MerchantRef>, val failedMerchants: List<String>, val total: Int,
    val hasNext: Boolean, val checkedAt: String
)

/** AppModel이 요청을 직렬화한다. 조건 변경·세션 종료 뒤 늦게 도착한 읽기 응답은 폐기한다. */
class Catalog(private val request: suspend (String, String, JsonElement?, Map<String, String>) -> JsonElement) {
    var filters by mutableStateOf(ProductFilters()); private set
    var page by mutableStateOf<Int?>(null); private set
    var result by mutableStateOf<ProductSearch?>(null); private set
    var merchants by mutableStateOf<List<MerchantRef>>(emptyList()); private set
    private var revision = 0

    fun edit(filters: ProductFilters) {
        if (this.filters == filters) return
        this.filters = filters
        revision++; page = null; result = null
    }

    suspend fun search(page: Int = 1) {
        val body = filters.body(page)
        val current = ++revision
        this.page = page; result = null
        try {
            val found = request("POST", "/products/search", body, emptyMap()).jsonObject.getValue("data").read<ProductSearch>()
            if (current != revision) return
            result = found
            if (filters.merchantId.isBlank()) merchants = found.merchants
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (current == revision) throw failure
        }
    }

    fun clear() {
        revision++; filters = ProductFilters(); page = null; result = null; merchants = emptyList()
    }
}

fun availabilityLabel(value: String): String = when (value) {
    "IN_STOCK" -> "판매 가능"
    "OUT_OF_STOCK" -> "품절"
    "DISCONTINUED" -> "판매 중지"
    else -> "판매 상태 확인 필요"
}
