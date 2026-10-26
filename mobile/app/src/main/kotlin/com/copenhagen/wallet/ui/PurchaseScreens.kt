@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.copenhagen.wallet.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.delay
import java.time.Instant

@Composable fun PurchaseInbox(app: AppModel) {
    Section("배송지 선택")
    Data(app.purchases.inbox, app.busy, "구매 요청을 불러오지 못했어요.",
        { app.execute { app.purchases.loadInbox() } }) { rows ->
        if (rows.isEmpty()) Text("선택할 배송지가 없어요", color = Carbon.Muted)
        rows.forEach { purchase -> key(purchase.purchaseRequestId) {
            val cart = app.purchases.inboxCarts[purchase.purchaseRequestId]
            val firstItem = cart?.items?.firstOrNull()
            Tile(onClick = { app.openPurchase(purchase.purchaseRequestId) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProductThumbnail(firstItem?.imageUrl, Modifier.size(72.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(firstItem?.productName ?: purchaseStatusLabel(purchase.status),
                            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (firstItem != null) Text("상품 ${cart?.items.orEmpty().sumOf { it.quantity }}개 · ${purchaseStatusLabel(purchase.status)}",
                            color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                        else Text(if (purchase.needsAddress) "받으실 배송지를 선택해 주세요" else "구매 진행 상황을 확인해 주세요",
                            color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Glyph(WalletIcon.ArrowRight, tint = Carbon.Muted)
                }
            }
        } }
    }
    if (app.purchases.inbox != null && app.purchases.inboxError != null) {
        Secondary("구매 요청 다시 불러오기", !app.busy) { app.execute { app.purchases.loadInbox() } }
    }
}

/** Selection, dispatch and the confirmed address share one physical surface. */
@Composable fun PurchaseScreen(app: AppModel) {
    val purchases = app.purchases
    val routeId = LocalDisplayedRoute.current.id
    val request = purchases.current?.takeIf { it.purchaseRequestId == routeId }
    val reduced = LocalReducedMotion.current
    val returnToCaller = LocalNotificationReturn.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val now by produceState(Instant.now(), routeId) { while (true) { value = Instant.now(); delay(1000) } }
    // Read-only, bounded follow-up while visible. Never resend selection or approve from a timer.
    LaunchedEffect(routeId, request?.preparing, focused) {
        if (request?.preparing == true && focused) repeat(10) {
            delay(1500)
            if (app.route == Route(Screen.U01, routeId) && !app.busy) app.execute { purchases.refresh() }
        }
    }
    Page {
        val accepted = purchases.confirmedSelection != null && request?.needsAddress == false
        Heading(if (accepted) "배송지 선택 완료" else "배송지 선택")
        Data(request, app.busy, "구매 요청을 불러오지 못했어요.",
            { routeId?.let(app::openPurchase) }) { value ->
            val expired = value.needsAddress && value.expired(now)
            val phase = when {
                purchases.submitting -> "sending"
                purchases.uncertain -> "unknown"
                value.needsAddress && !expired -> "select"
                else -> "result"
            }
            purchases.cart?.takeIf { it.items.isNotEmpty() }?.let { cart ->
                Tile {
                    Text("주문 상품 ${cart.items.sumOf { it.quantity }}개", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(color = Carbon.Line)
                    OrderItemRows(cart.items.map {
                        ItemSummary(it.productName, quantity = it.quantity, amountMinor = it.unitPrice.amount, imageUrl = it.imageUrl)
                    }, cart.summary.currency)
                }
            }
            if (phase == "select") {
                val seconds = (Instant.parse(value.expiresAt).epochSecond - now.epochSecond).coerceAtLeast(0)
                Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} 남음",
                    color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedContent(phase, label = "shipping-selection-receipt", transitionSpec = {
                val transform = if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(Motion.Content)) + expandVertically(Motion.surface())) togetherWith fadeOut(tween(100))
                transform.using(if (reduced) null else SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> Motion.surface() }))
            }) { displayed ->
                CompositionLocalProvider(LocalVisibilityScope provides this) {
                    Column(Modifier.fillMaxWidth().then(if (displayed != phase) Modifier.clearAndSetSemantics {} else Modifier),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (displayed == "select") {
                            Data(purchases.addresses, app.busy, "배송지를 불러오지 못했어요.",
                                { app.execute { purchases.refresh() } }) { addresses ->
                                if (addresses.isEmpty()) QuietEmptyState(WalletIcon.Location, "등록된 배송지가 없어요")
                                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    addresses.sortedByDescending { it.isDefault }.forEach { address -> key(address.id) {
                                        ShippingAddress(address, value.purchaseRequestId, purchases.selectedId == address.id,
                                            !app.busy, onSelect = { purchases.select(address.id) })
                                    } }
                                }
                            }
                            if (purchases.addresses.orEmpty().size < 20) Secondary("배송지 추가", !app.busy, onClick = app::addPurchaseAddress)
                            Action("이 배송지로 선택", enabled = purchases.selected != null && !app.busy, onClick = app::confirmPurchaseAddress)
                        } else {
                            val address = purchases.confirmedSelection ?: purchases.selected
                            address?.let { ShippingAddress(it, value.purchaseRequestId, selected = true) }
                            Tile {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Glyph(if (expired || value.terminal) WalletIcon.Info else WalletIcon.Clock,
                                        tint = Carbon.Muted)
                                    Text(when (displayed) {
                                        "sending" -> "선택을 보내는 중"
                                        "unknown" -> "선택 결과 확인 필요"
                                        else -> if (expired) "요청이 만료되었어요" else purchaseStatusLabel(value.status)
                                    }, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                        style = MaterialTheme.typography.titleLarge)
                                    ReadActivity(purchases.submitting, activeDescription = "선택을 보내는 중")
                                }
                                if (displayed == "unknown") Text("선택 결과를 아직 확인하지 못했어요.", color = Carbon.Muted)
                            }
                            if (displayed != "sending") {
                                value.approvalIds.forEachIndexed { index, id ->
                                    Action(if (value.approvalIds.size == 1) "결제 요청 보기" else "결제 요청 ${index + 1} 보기", !app.busy) {
                                        app.openPurchaseApproval(id)
                                    }
                                }
                                if (displayed == "unknown") {
                                    Action("선택 결과 확인", !app.busy) { app.execute { purchases.refresh() } }
                                    Secondary("같은 선택 다시 보내기", !app.busy, onClick = app::retryPurchaseAddress)
                                } else if (!value.terminal && !expired && value.approvalIds.isEmpty()) {
                                    Secondary("상태 확인", enabled = true, loading = app.busy) { app.execute { purchases.refresh() } }
                                }
                            }
                        }
                    }
                }
            }
            TechnicalDetails("purchase-${value.purchaseRequestId}", "요청 상세 정보") {
                DetailLine("구매 요청 번호", value.purchaseRequestId)
            }
        }
        if (returnToCaller != null) {
            if (request != null && !request.needsAddress && request.approvalIds.isEmpty() && !purchases.uncertain && !purchases.submitting) {
                Action("돌아가기", onClick = returnToCaller)
            } else Secondary("돌아가기", onClick = returnToCaller)
        } else if (request != null && !request.needsAddress) {
            Secondary("결제로", !app.busy || purchases.submitting) { app.selectTab(Screen.P01) }
        }
    }
}

@Composable private fun ShippingAddress(address: Address, purchaseId: String, selected: Boolean,
    enabled: Boolean = true, onSelect: (() -> Unit)? = null) {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val color by animateColorAsState(if (selected) Carbon.Blue else Carbon.Boundary,
        if (reduced) snap() else tween(Motion.Content), label = "shipping-selection-outline")
    val elevation by animateDpAsState(if (selected) 3.dp else 0.dp,
        if (reduced) snap() else Motion.control(.1.dp), label = "shipping-selection-lift")
    Surface(Modifier.fillMaxWidth().sharedObject("shipping-$purchaseId-${address.id}")
        .then(if (onSelect == null) Modifier else Modifier.pressMotion(interaction).selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onSelect)),
        shape = RoundedCornerShape(24.dp), color = Carbon.Surface, shadowElevation = elevation,
        border = BorderStroke(if (selected || focused) 2.dp else 1.dp, if (focused) Carbon.Blue else color)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(address.recipient, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (onSelect != null) RadioButton(selected, onClick = null, enabled = enabled)
                else Glyph(WalletIcon.Location, tint = Carbon.Blue)
            }
            if (address.isDefault) Text("기본 배송지", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
            Text(address.address, style = MaterialTheme.typography.bodyLarge)
            if (address.detail.isNotBlank()) Text(address.detail)
            Text(address.phone, color = Carbon.Muted)
            if (address.memo.isNotBlank()) Text(address.memo, color = Carbon.Muted)
        }
    }
}
