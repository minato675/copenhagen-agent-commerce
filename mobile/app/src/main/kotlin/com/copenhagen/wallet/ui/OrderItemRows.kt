package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.data.ItemSummary
import com.copenhagen.wallet.data.approvalMoney

/** Keep each product and its options together, including when names wrap at a larger text size. */
@Composable internal fun OrderItemRows(items: List<ItemSummary>, currency: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = Carbon.Line)
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val compact = LocalDensity.current.fontScale > 1.3f || LocalConfiguration.current.screenWidthDp < 360
                ProductThumbnail(item.imageUrl, Modifier.size(if (compact) 56.dp else 72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(item.option?.takeIf { it.isNotBlank() }, "${item.quantity}개").joinToString(" · "),
                        color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                    if (currency != null) item.amountMinor?.let { amount ->
                        Text("개당 ${approvalMoney(amount, currency)}", color = Carbon.Muted,
                        style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
