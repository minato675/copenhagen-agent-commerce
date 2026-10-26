package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Adjacent product text supplies the accessible name; keep the entire product in view. */
@Composable internal fun ProductThumbnail(imageUrl: String?, modifier: Modifier = Modifier) {
    RemoteImage(imageUrl, null, modifier.size(80.dp).clearAndSetSemantics {})
}
