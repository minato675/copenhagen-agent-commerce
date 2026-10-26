package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ProductSelection(val productId: String, val merchantId: String, val optionId: String? = null)
@Serializable data class ProductSpec(val name: String, val value: String)
@Serializable
data class ProductVariant(val optionId: String, val label: String, val price: Long?, val availability: String, val purchaseAllowed: Boolean? = null)
@Serializable data class ProductShipping(val status: String, val fee: Long?, val description: String)
@Serializable
data class ProductDetail(
    val productId: String, val name: String, val brand: String, val category: String,
    val imageUrl: String? = null, val description: String? = null, val specs: List<ProductSpec>,
    val offer: ProductOffer, val variants: List<ProductVariant>, val optionsComplete: Boolean,
    val optionsRequired: Boolean? = null, val selectionStatus: String, val selectedOptionId: String? = null,
    val stockQuantity: Long? = null, val shipping: ProductShipping
)
@Serializable
data class ProductDetailResult(val status: String, val request: ProductSelection, val product: ProductDetail?, val checkedAt: String)

class ProductDetails(private val request: suspend (String, String, JsonElement?, Map<String, String>) -> JsonElement) {
    var selection by mutableStateOf<ProductSelection?>(null); private set
    var result by mutableStateOf<ProductDetailResult?>(null); private set
    private var revision = 0

    suspend fun load(selection: ProductSelection) {
        val id = selection.productId
        // Http는 경로 전체를 받는다. 동적 ID가 경로 구분자·상위 경로로 해석되지 않게 한다.
        require(id.isNotBlank() && id !in setOf(".", "..") && id.none { it == '/' || it == '\\' }) { "상품 정보를 확인할 수 없어요." }
        require(selection.merchantId.isNotBlank()) { "판매처를 선택해 주세요." }
        val query = buildMap {
            put("merchantId", selection.merchantId)
            selection.optionId?.takeIf { it.isNotBlank() }?.let { put("optionId", it) }
        }
        val current = ++revision
        this.selection = selection; result = null
        try {
            val found = request("GET", "/products/$id", null, query).jsonObject.getValue("data").read<ProductDetailResult>()
            check(found.status == "NOT_FOUND" || (found.status == "FOUND" && found.product != null)) { "상품 응답을 확인할 수 없어요." }
            if (current == revision) result = found
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (current == revision) throw failure
        }
    }

    suspend fun reload() { load(checkNotNull(selection) { "상품을 다시 선택해 주세요." }) }

    fun clear() { revision++; selection = null; result = null }
}
