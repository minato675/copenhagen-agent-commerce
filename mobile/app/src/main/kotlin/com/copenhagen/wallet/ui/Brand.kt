package com.copenhagen.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.R

/** The approved full-color artwork; never tint or crop the wallet symbol. */
@Composable
fun BrandLogo(modifier: Modifier = Modifier, size: Dp = 32.dp, contentDescription: String? = null) {
    Image(
        painter = painterResource(R.drawable.copenhagen_logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size).clip(RoundedCornerShape(percent = 28)),
        contentScale = ContentScale.Fit
    )
}

@Composable
fun BrandWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The adjacent app name already supplies the accessible label.
        BrandLogo(size = 28.dp)
        Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
