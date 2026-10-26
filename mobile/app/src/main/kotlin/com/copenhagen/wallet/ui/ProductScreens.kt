package com.copenhagen.wallet.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.AppModel
import com.copenhagen.wallet.data.money
import com.copenhagen.wallet.features.*

@Composable fun ProductSearchScreen(app: AppModel) = Page {
    val catalog = app.catalog
    val filters = catalog.filters
    var expanded by rememberSaveable { mutableStateOf(false) }
    Heading("상품 탐색")
    OutlinedTextField(filters.query, { catalog.edit(filters.copy(query = it.take(200))) }, Modifier.fillMaxWidth(),
        label = { Text("상품 검색") }, singleLine = true, enabled = !app.busy, shape = RoundedCornerShape(16.dp),
        trailingIcon = { IconButton({ expanded = false; app.execute { catalog.search() } }, enabled = !app.busy) {
            Glyph(WalletIcon.Search, "검색")
        } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { expanded = false; app.execute { catalog.search() } }))
    TextButton({ expanded = !expanded }, enabled = !app.busy) {
        Glyph(WalletIcon.Settings, modifier = Modifier.size(20.dp), tint = Carbon.Blue)
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "필터 접기" else if (filters.copy(query = "") != ProductFilters()) "검색 필터 · 적용 중" else "검색 필터")
    }
    MotionReveal(expanded) {
        Tile {
            Section("판매처")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(MerchantRef("", "전체")) + catalog.merchants).forEach { merchant ->
                    FilterChip(filters.merchantId == merchant.merchantId, { catalog.edit(filters.copy(merchantId = merchant.merchantId)) },
                        { Text(merchant.name) }, enabled = !app.busy, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Field(filters.minPrice, { catalog.edit(filters.copy(minPrice = it)) }, "최저 가격 (원)",
                keyboard = KeyboardType.Number, maxLength = 19, enabled = !app.busy)
            Field(filters.maxPrice, { catalog.edit(filters.copy(maxPrice = it)) }, "최고 가격 (원)",
                keyboard = KeyboardType.Number, maxLength = 19, enabled = !app.busy)
            Section("정렬")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductSort.entries.forEach { sort ->
                    FilterChip(filters.sort == sort, { catalog.edit(filters.copy(sort = sort)) }, { Text(sort.label) },
                        enabled = !app.busy, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Action("필터 적용", !app.busy) { expanded = false; app.execute { catalog.search() } }
        }
    }
    if (catalog.page != null) Data(catalog.result, app.busy, "검색 결과를 불러오지 못했어요.",
        { app.execute { catalog.search(catalog.page ?: 1) } }) { result ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${result.total}개 상품", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(filters.sort.label, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (result.failedMerchants.isNotEmpty()) {
            val names = result.failedMerchants.mapNotNull { id ->
                catalog.merchants.find { it.merchantId == id }?.name?.takeIf { it.isNotBlank() }
            }
            val unnamed = result.failedMerchants.size - names.size
            val sellers = names.joinToString(" · ") + if (unnamed > 0) " 외 ${unnamed}곳" else ""
            Notice(if (names.isEmpty()) "일부 판매처의 상품을 불러오지 못했어요." else "${sellers}의 상품을 불러오지 못했어요.")
        }
        if (result.products.isEmpty()) QuietEmptyState(WalletIcon.Search,
            if (result.status == "PARTIAL") "일부 판매처를 확인하지 못했어요" else "검색 결과가 없어요")
        result.products.forEachIndexed { index, product -> key(index, product.productId) {
            SearchProductCard(product, enabled = !app.busy) { offer ->
                app.openProduct(product.productId, offer.merchantId)
            }
        } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ app.execute { catalog.search((catalog.page ?: 1) - 1) } }, enabled = !app.busy && (catalog.page ?: 1) > 1) {
                Glyph(WalletIcon.Back, "이전 페이지")
            }
            Text("${catalog.page} 페이지", color = Carbon.Muted)
            IconButton({ app.execute { catalog.search((catalog.page ?: 1) + 1) } }, enabled = !app.busy && result.hasNext) {
                Glyph(WalletIcon.ArrowRight, "다음 페이지")
            }
        }
    }
}

@Composable private fun SearchProductCard(product: CatalogProduct, enabled: Boolean, onOffer: (ProductOffer) -> Unit) {
    val singleOffer = product.offers.singleOrNull()
    val interaction = remember(product.productId) { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(20.dp)
    val cardModifier = Modifier.fillMaxWidth().pressMotion(interaction)
        .then(if (focused) Modifier.border(2.dp, Carbon.Blue, shape) else Modifier)
    val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val largeText = LocalDensity.current.fontScale >= 1.5f
            val imageSize = if (maxWidth < 300.dp || largeText) 80.dp else 96.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    ProductThumbnail(product.imageUrl, Modifier.size(imageSize))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        if (singleOffer != null) {
                            Text(singleOffer.merchantName, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                            if (!largeText) SearchOfferSummary(singleOffer)
                        } else {
                            listOf(product.brand, product.category).filter { it.isNotBlank() }
                                .joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                    Text(it, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                                }
                            Text(if (product.offers.isEmpty()) "판매처가 없어요" else "판매처 ${product.offers.size}곳",
                                color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                // At larger font sizes, the price gets the full card width instead of a narrow text column.
                if (singleOffer != null && largeText) SearchOfferSummary(singleOffer)
                if (product.offers.size > 1) product.offers.forEach { offer ->
                    HorizontalDivider(color = Carbon.Line)
                    SearchMerchantOffer(offer, enabled) { onOffer(offer) }
                }
            }
        }
    }
    if (singleOffer != null) {
        Surface(onClick = { onOffer(singleOffer) }, enabled = enabled, interactionSource = interaction,
            modifier = cardModifier, shape = shape, color = Carbon.Surface, content = content)
    } else {
        Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = Carbon.Surface, content = content)
    }
}

@Composable private fun SearchOfferSummary(offer: ProductOffer) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(offer.price?.let { money(it, offer.currency) } ?: "가격 미정", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge)
            Glyph(WalletIcon.ArrowRight, "상품 상세 보기", Modifier.size(20.dp), Carbon.Muted)
        }
        Availability(offer.availability)
    }
}

@Composable private fun SearchMerchantOffer(offer: ProductOffer, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember(offer.merchantId) { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, interactionSource = interaction, shape = shape,
        modifier = Modifier.pressMotion(interaction)
            .then(if (focused) Modifier.border(2.dp, Carbon.Blue, shape) else Modifier)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(offer.merchantName, style = MaterialTheme.typography.titleMedium)
            SearchOfferSummary(offer)
        }
    }
}

@Composable fun ProductDetailScreen(app: AppModel) = Page {
    val details = app.products
    var informationExpanded by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("상품 상세", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        IconButton({ app.execute { details.reload() } }, enabled = !app.busy) { Glyph(WalletIcon.Refresh, "새로고침") }
    }
    Data(details.result, app.busy, "상품을 불러오지 못했어요.", { app.execute { details.reload() } }) { result ->
        if (result.status == "NOT_FOUND") {
            QuietEmptyState(WalletIcon.Box, "상품을 찾을 수 없어요")
        } else result.product?.let { product ->
            Surface(shape = RoundedCornerShape(24.dp), color = Carbon.Surface) {
                RemoteImage(product.imageUrl, product.name, Modifier.fillMaxWidth().height(220.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(product.offer.merchantName, color = Carbon.Muted)
                Text(product.name, style = MaterialTheme.typography.headlineSmall)
                ValueText(product.offer.price?.let { money(it, product.offer.currency) } ?: "가격 미정", style = MaterialTheme.typography.headlineMedium)
                Availability(product.offer.availability)
            }
            if (product.variants.isNotEmpty() || product.optionsRequired == true) Section(if (product.optionsRequired == true) "필수 옵션" else "옵션")
            MotionReveal(product.selectionStatus == "INVALID") {
                Text("선택할 수 없는 옵션이에요.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Carbon.Error)
            }
            product.variants.forEach { variant ->
                FilterChip(selected = product.selectedOptionId == variant.optionId,
                    onClick = { details.selection?.let { selection ->
                        app.execute { details.load(selection.copy(optionId = variant.optionId)) }
                    } }, enabled = !app.busy,
                    label = {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(variant.label, style = MaterialTheme.typography.titleMedium)
                                Text(variant.price?.let { money(it, product.offer.currency) } ?: "가격 미정", style = MaterialTheme.typography.bodyLarge)
                                Availability(variant.availability)
                                Text(when (variant.purchaseAllowed) { true -> "구매 가능"; false -> "구매 불가"; null -> "구매 가능 여부 확인 필요" },
                                    color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                            }
                            Glyph(if (product.selectedOptionId == variant.optionId) WalletIcon.Check else WalletIcon.Add,
                                if (product.selectedOptionId == variant.optionId) "선택됨" else "옵션 선택", tint = Carbon.Blue)
                        }
                    }, shape = RoundedCornerShape(20.dp))
            }
            val shipping = product.shipping
            Tile {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Glyph(WalletIcon.Box, tint = Carbon.Muted)
                    DetailLine("재고", product.stockQuantity?.let { if (it == 0L) "품절" else "${it}개" } ?: "확인 필요")
                }
                HorizontalDivider(color = Carbon.Line)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Glyph(WalletIcon.Location, tint = Carbon.Muted)
                    DetailLine("배송비", if (shipping.status == "UNKNOWN" || shipping.fee == null) "확인 필요" else money(shipping.fee, product.offer.currency))
                }
            }
            TextButton({ informationExpanded = !informationExpanded }) {
                Text(if (informationExpanded) "상품 정보 접기" else "상품 정보")
                Spacer(Modifier.width(8.dp))
                Glyph(WalletIcon.ChevronDown, modifier = Modifier.size(20.dp), tint = Carbon.Blue)
            }
            MotionReveal(informationExpanded) {
                Tile {
                    if (product.brand.isNotBlank()) DetailLine("브랜드", product.brand)
                    if (product.category.isNotBlank()) DetailLine("분류", product.category)
                    product.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    product.specs.forEach { spec -> DetailLine(spec.name, spec.value) }
                    if (shipping.description.isNotBlank()) DetailLine("배송 안내", shipping.description)
                    if (!product.optionsComplete) Notice("옵션 정보가 아직 확정되지 않았어요.")
                    DetailLine("확인 일시", result.checkedAt)
                }
            }
        }
    }
}

@Composable private fun Availability(value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (value == "IN_STOCK") Glyph(WalletIcon.Check, modifier = Modifier.size(18.dp), tint = Carbon.Positive)
        Text(availabilityLabel(value), color = if (value == "IN_STOCK") Carbon.Positive else Carbon.Muted,
            style = MaterialTheme.typography.bodyMedium)
    }
}
