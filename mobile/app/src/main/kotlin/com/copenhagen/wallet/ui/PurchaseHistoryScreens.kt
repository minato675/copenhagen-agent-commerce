package com.copenhagen.wallet.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*

internal fun LazyListScope.purchaseHistoryList(app: AppModel, query: String, onQuery: (String) -> Unit) {
    val history = app.purchaseHistory
    item(key = "purchases-description", contentType = "description") {
        Text("AI로 요청한 최근 구매를 한 번에 확인하세요.", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
    }
    item(key = "purchases-filters", contentType = "filters") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PurchaseHistoryFilter.entries.forEach { filter ->
                HistoryFilter(history.filter == filter, filter.label, app.busy) { history.filter = filter }
            }
        }
    }
    item(key = "purchases-search", contentType = "search") { Field(query, onQuery, "상품·판매처 검색", maxLength = 100) }
    history.listError?.let { message ->
        item(key = "purchases-error", contentType = "notice") {
            Notice("기록을 새로 불러오지 못했어요. $message" + if (history.entries != null) " 아래는 마지막으로 확인한 기록이에요." else "")
        }
    }
    history.listNotice?.let { message -> item(key = "purchases-notice", contentType = "notice") { Notice(message) } }
    if (query.isNotBlank()) history.searchNotice?.let { message ->
        item(key = "purchases-search-notice", contentType = "notice") { Notice(message) }
    }
    historyData("purchases", history.entries, app.busy, "구매 기록을 불러오지 못했어요.", { app.execute { history.load() } }) { entries ->
        val shown = entries.filter { it.matches(history.filter, query) }
        val searching = query.isNotBlank() && history.searchLoading
        val incompleteSearch = query.isNotBlank() && history.searchNotice != null
        item(key = "purchases-count", contentType = "count") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val count = if (searching || incompleteSearch) "${shown.size}건 확인" else "${shown.size}건"
                Text("$count · 최근 요청순", Modifier.weight(1f), color = Carbon.Muted,
                    style = MaterialTheme.typography.bodyMedium)
                ReadActivity(app.busy || history.searchLoading)
            }
        }
        if (shown.isEmpty()) item(key = "purchases-empty", contentType = "empty") {
            when {
                searching -> QuietEmptyState(WalletIcon.History, "구매 상품을 찾고 있어요",
                    "상품과 판매처 정보를 확인하고 있어요.")
                incompleteSearch -> QuietEmptyState(WalletIcon.History, "검색을 끝내지 못했어요",
                    "구매 기록을 다시 불러오면 상품과 판매처 정보를 다시 확인할 수 있어요.")
                else -> QuietEmptyState(WalletIcon.History,
                    if (entries.isEmpty()) "아직 구매 기록이 없어요" else "조건에 맞는 구매가 없어요",
                    if (entries.isEmpty()) "이전 주문은 주문·배송에서 확인할 수 있어요." else "검색어나 선택한 상태를 바꿔 보세요.")
            }
        }
        items(shown, key = { "purchase-${it.id}" }, contentType = { "purchase" }) { entry ->
            LaunchedEffect(entry.id, history.imageRevision) { history.ensureThumbnail(entry.id) }
            PurchaseRecordTile(entry, !app.busy) { app.openPurchaseRecord(entry.id) }
        }
        if (history.listError != null || history.listNotice != null || incompleteSearch) item(key = "purchases-retry", contentType = "retry") {
            Secondary("구매 기록 다시 불러오기", !app.busy) { app.execute { history.load() } }
        }
    }
    item(key = "purchases-limit", contentType = "footnote") {
        Text("최근 AI 구매 요청을 최대 50건까지 보여드려요.", color = Carbon.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PurchaseRecordTile(entry: PurchaseHistoryEntry, enabled: Boolean, onClick: () -> Unit) {
    val items = entry.checkouts.flatMap(entry::items)
    val firstItem = items.firstOrNull { !it.imageUrl.isNullOrBlank() } ?: items.firstOrNull()
    Tile(onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductThumbnail(firstItem?.imageUrl, Modifier.size(72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(firstItem?.name ?: entry.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (firstItem != null) Text(entry.title + if (items.size > 1) " · 상품 ${items.size}종" else "",
                    color = Carbon.Muted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                else if (entry.checkouts.isNotEmpty()) Text("판매처 ${entry.checkouts.size}곳",
                    color = Carbon.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Glyph(WalletIcon.ArrowRight, modifier = Modifier.size(20.dp), tint = Carbon.Muted)
        }
        PurchaseStatus(entry.status)
        if (entry.amounts.isEmpty()) Text("결제 금액 확정 전", color = Carbon.Muted)
        entry.amounts.forEach { amount ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("결제 완료", color = Carbon.Muted, style = MaterialTheme.typography.bodySmall)
                    Text(approvalMoney(amount.completed, amount.currency), style = MaterialTheme.typography.titleLarge)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("요청 금액", color = Carbon.Muted, style = MaterialTheme.typography.bodySmall)
                    Text(approvalMoney(amount.requested, amount.currency), color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (entry.checkouts.size > 1) Text("${entry.checkouts.size}곳 중 ${entry.completedCount}곳 결제 완료",
            color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable fun PurchaseHistoryDetailScreen(app: AppModel) = Page {
    val history = app.purchaseHistory
    val id = LocalDisplayedRoute.current.id
    val entry = history.detail?.takeIf { it.id == id }
    Heading("구매 상세")
    history.detailError?.let { Notice("최신 결과를 불러오지 못했어요. $it" + if (entry != null) " 아래는 마지막으로 확인한 결과예요." else "") }
    Data(entry, app.busy, "구매 기록을 불러오지 못했어요.", { id?.let(app::openPurchaseRecord) }) { record ->
        Tile {
            Text(record.title, style = MaterialTheme.typography.headlineSmall)
            PurchaseStatus(record.status)
            if (record.amounts.isEmpty()) Text("결제 금액이 아직 확정되지 않았어요.", color = Carbon.Muted)
            record.amounts.forEachIndexed { index, amount ->
                if (index > 0) HorizontalDivider(color = Carbon.Line)
                Text("결제 완료 금액", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                Text(approvalMoney(amount.completed, amount.currency), style = MaterialTheme.typography.headlineLarge)
                MoneyLine("요청 금액", amount.requested, amount.currency)
                if (record.checkouts.any { it.currency == amount.currency && it.status in setOf("CREATING", "AWAITING_APPROVAL", "COMPLETING") })
                    MoneyLine("처리 중·승인 대기", amount.processing, amount.currency)
                if (record.checkouts.any { it.currency == amount.currency && it.status in setOf("FAILED", "CANCELLED", "REJECTED", "EXPIRED") })
                    MoneyLine("결제되지 않은 금액", amount.unpaid, amount.currency)
                if (record.status == "UNKNOWN") MoneyLine("확인 필요한 금액", amount.unknown, amount.currency)
            }
            if (record.checkouts.isNotEmpty()) Text("${record.checkouts.size}곳 중 ${record.completedCount}곳 결제 완료",
                color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (record.status == "PARTIALLY_COMPLETED") Notice("일부 판매처만 결제됐어요. 실패하거나 취소된 판매처의 결과를 확인해 주세요.")
        if (record.inProgress) Notice("아직 진행 중인 구매예요. 새로고침하면 최신 결과를 확인할 수 있어요.")
        if (record.status == "UNKNOWN") Notice("확인되지 않은 판매처 상태가 있어요. 결제 완료 여부를 다시 확인해 주세요.")
        history.detailNotice?.let { Notice(it) }
        if (record.checkouts.isNotEmpty()) Section("판매처별 결제 결과")
        record.checkouts.forEach { checkout -> key(checkout.merchantId) {
            Tile {
                Text(record.merchantName(checkout), style = MaterialTheme.typography.titleLarge)
                PurchaseStatus(checkout.status, merchant = true)
                Text(approvalMoney(checkout.totalAmount, checkout.currency), style = MaterialTheme.typography.titleLarge)
                val items = record.items(checkout)
                if (items.isNotEmpty()) {
                    HorizontalDivider(color = Carbon.Line)
                    OrderItemRows(items)
                }
            }
        } }
        if (record.approvalIds.isNotEmpty()) {
            Section("결제 승인")
            Text("승인 금액은 결제에 동의한 금액이에요. 실제 결제 완료 금액과 다를 수 있어요.",
                color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
            record.approvalIds.forEachIndexed { index, approvalId -> key(approvalId) {
                Tile {
                    Text(record.approvals[approvalId]?.displayName?.takeIf { it.isNotBlank() }
                        ?: "결제 승인 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    record.approvals[approvalId]?.let { approval ->
                        StatusPill(approval.status)
                        MoneyLine("승인 요청 금액", approval.amountMinor, approval.currency)
                    }
                    Secondary(if (record.approvals[approvalId]?.actionable == true) "승인 요청 확인" else "승인 내역 보기", !app.busy) {
                        app.openRecordedApproval(approvalId)
                    }
                }
            } }
        }
        if (record.purchase.needsAddress) Action("배송지 선택하기", !app.busy) { app.openPurchase(record.id) }
        Secondary("최신 결과 확인", loading = app.busy) { app.execute { history.open(record.id) } }
        Secondary("주문·배송 보기", !app.busy) {
            app.historyTab = HistoryTab.ORDERS
            app.selectTab(Screen.H01)
        }
        TechnicalDetails("purchase-history-${record.id}", "구매 상세 정보") {
            DetailLine("구매 요청 번호", record.id)
            record.approvalIds.forEachIndexed { index, approvalId ->
                DetailLine(if (record.approvalIds.size == 1) "승인 번호" else "승인 번호 ${index + 1}", approvalId)
            }
            record.checkouts.filter { record.merchant(it) == null }.forEach { checkout ->
                DetailLine("${record.merchantName(checkout)} 판매처 번호", checkout.merchantId)
            }
        }
    }
}

@Composable private fun MoneyLine(label: String, amount: Long, currency: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(approvalMoney(amount, currency), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun PurchaseStatus(status: String, merchant: Boolean = false) {
    val positive = status == "COMPLETED"
    val pending = status in setOf("PENDING_ADDRESS", "CREATING_CHECKOUTS", "CREATING", "AWAITING_APPROVAL", "COMPLETING")
    val color = when { positive -> Carbon.Positive; pending || status == "PARTIALLY_COMPLETED" -> Carbon.Warning
        status == "FAILED" -> Carbon.Error; else -> Carbon.Muted }
    Surface(color = color.copy(alpha = .08f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Glyph(if (positive) WalletIcon.Check else if (pending) WalletIcon.Clock else WalletIcon.Info,
                modifier = Modifier.size(16.dp), tint = color)
            Text(if (merchant) checkoutStatusLabel(status) else purchaseStatusLabel(status),
                style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
