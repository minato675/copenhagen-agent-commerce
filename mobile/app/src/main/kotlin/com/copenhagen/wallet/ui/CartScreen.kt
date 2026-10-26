package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.AppModel
import com.copenhagen.wallet.Screen
import com.copenhagen.wallet.data.money
import com.copenhagen.wallet.features.CartItem

@Composable fun CartScreen(app: AppModel) {
    var deleting by remember { mutableStateOf<CartItem?>(null) }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("장바구니", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            IconButton({ app.execute { app.cart.load() } }, enabled = !app.busy) { Glyph(WalletIcon.Refresh, "새로고침") }
        }
        app.cart.notice?.let { message -> Notice(when (message) {
            "장바구니 변경됨" -> "장바구니가 변경됐어요."
            "변경 결과 미확인" -> "변경 결과를 확인하지 못했어요."
            else -> message
        }) }
        Data(app.cart.view, app.busy, "장바구니를 불러오지 못했어요.",
            { app.execute { app.cart.load() } }) { cart ->
            if (cart.items.isEmpty()) {
                QuietEmptyState(WalletIcon.Bag, "장바구니가 비어 있어요")
                Secondary("상품 탐색", !app.busy) { app.open(Screen.G01) }
            }
            cart.items.forEach { item -> key(item.cartItemId) {
                Tile {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val compact = LocalDensity.current.fontScale > 1.3f || LocalConfiguration.current.screenWidthDp < 360
                        ProductThumbnail(item.imageUrl, Modifier.size(if (compact) 56.dp else 80.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.productName, style = MaterialTheme.typography.titleMedium)
                            val merchant = app.catalog.merchants.firstOrNull { it.merchantId == item.merchantId }?.name
                                ?: app.catalog.result?.products?.asSequence()?.flatMap { it.offers.asSequence() }
                                    ?.firstOrNull { it.merchantId == item.merchantId }?.merchantName
                            Text(merchant?.takeIf { it.isNotBlank() } ?: "판매처 정보 확인 필요", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                            Text("개당 ${money(item.unitPrice.amount, item.unitPrice.currency)}", color = Carbon.Muted)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(50), color = Carbon.Layer) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ app.execute { app.cart.updateQuantity(item.cartItemId, item.quantity - 1) } },
                                    enabled = !app.busy && item.quantity > 1) { Glyph(WalletIcon.Minus, "${item.productName} 수량 줄이기") }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    ValueText("${item.quantity}개", style = MaterialTheme.typography.titleMedium)
                                }
                                IconButton({ app.execute { app.cart.updateQuantity(item.cartItemId, item.quantity + 1) } },
                                    enabled = !app.busy && item.quantity < Int.MAX_VALUE) { Glyph(WalletIcon.Add, "${item.productName} 수량 늘리기") }
                            }
                        }
                        IconButton({ deleting = item }, enabled = !app.busy) { Glyph(WalletIcon.Trash, "${item.productName} 삭제", tint = Carbon.Muted) }
                    }
                    ValueText(money(item.lineTotal, item.unitPrice.currency), style = MaterialTheme.typography.titleLarge)
                    if (item.optionValueIds.isNotEmpty()) TechnicalDetails(item.cartItemId, title = "옵션 정보") {
                        DetailLine("옵션 식별자", item.optionValueIds.joinToString())
                    }
                }
            } }
            if (cart.items.isNotEmpty()) {
                val summary = cart.summary
                Tile {
                    Text("예상 금액", style = MaterialTheme.typography.titleLarge)
                    listOf("상품" to summary.itemsTotal, "할인" to summary.discountTotal, "배송" to summary.shippingFee).forEach { (label, amount) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(label, Modifier.weight(1f), color = Carbon.Muted)
                            Text(money(amount, summary.currency), Modifier.weight(2f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                    HorizontalDivider(color = Carbon.Line)
                    Text("예상 합계", color = Carbon.Muted)
                    ValueText(money(summary.estimatedTotal, summary.currency), style = MaterialTheme.typography.headlineMedium)
                    Text("할인과 배송비는 아직 확정되지 않았어요.", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    deleting?.let { item -> Confirm("${item.productName} 삭제", "삭제", { deleting = null }) {
        deleting = null
        app.execute { app.cart.remove(item.cartItemId) }
    } }
}
