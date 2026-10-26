package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class CartView(val cartId: String?, val version: Int, val items: List<CartItem>, val summary: CartSummary)
@Serializable
data class CartItem(
    val cartItemId: String, val merchantId: String, val productId: String,
    val optionValueIds: List<String>, val productName: String,
    val productUrl: String? = null, val imageUrl: String? = null,
    val quantity: Int, val unitPrice: CartPrice, val lineTotal: Long
)
@Serializable data class CartPrice(val amount: Long, val currency: String)
@Serializable
data class CartSummary(
    val itemsTotal: Long, val discountTotal: Long, val shippingFee: Long,
    val estimatedTotal: Long, val currency: String
)
@Serializable private data class CartVersion(val cartVersion: Int)

/** AppModel.execute가 요청을 직렬화하며 세션 종료 시 clear한다. */
class Cart(private val request: suspend (String, String, JsonElement?, Map<String, String>) -> JsonElement) {
    var view by mutableStateOf<CartView?>(null); private set
    var notice by mutableStateOf<String?>(null); private set

    suspend fun load() {
        notice = null
        reload()
    }

    private suspend fun reload() {
        view = null
        view = request("GET", "/cart", null, emptyMap()).read<CartView>()
    }

    suspend fun updateQuantity(id: String, quantity: Int) {
        require(quantity >= 1) { "수량은 1개 이상이어야 해요." }
        change(id, quantity)
    }

    suspend fun remove(id: String) { change(id, null) }

    private suspend fun change(id: String, quantity: Int?) {
        val previous = checkNotNull(view) { "장바구니를 먼저 새로고침해 주세요." }
        require(validId(id) && previous.items.any { it.cartItemId == id }) { "상품 정보를 다시 불러와 주세요." }
        notice = null
        try {
            val body = quantity?.let { buildJsonObject { put("cartVersion", previous.version); put("quantity", it) } }
            val query = if (quantity == null) mapOf("cartVersion" to previous.version.toString()) else emptyMap()
            val updated = request(if (quantity == null) "DELETE" else "PATCH", "/cart/items/$id", body, query).read<CartVersion>()
            check(updated.cartVersion >= 0) { "장바구니 버전을 확인할 수 없어요." }
            view = previous.copy(version = updated.cartVersion)
        } catch (cancelled: CancellationException) {
            view = null
            throw cancelled
        } catch (failure: ApiError) {
            if (failure.code in setOf("CART_VERSION_CONFLICT", "CART_ITEM_NOT_FOUND")) {
                notice = "장바구니 변경됨"
                reload()
                return
            }
            if (failure.status >= 500) unknownResult()
            throw failure
        } catch (failure: Exception) {
            unknownResult()
            throw failure
        }
        reload()
    }

    private fun unknownResult() {
        // 응답 유실 시 같은 버전으로 쓰기를 반복하지 않는다. GET 성공 후에만 다시 편집한다.
        view = null
        notice = "변경 결과 미확인"
    }

    fun clear() { view = null; notice = null }
}
