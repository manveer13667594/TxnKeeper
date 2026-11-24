package com.manu.txnkeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Pieces that keep the Add and Edit forms short: two pickers per row, and the type next to the amount. */

/**
 * A tap-to-change value (account, payment method, date, time) as one slim pill: icon and text.
 * Two of them share a row, so four pickers take two rows instead of four.
 */
@Composable
fun PickChip(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    /** True for a placeholder such as "Payment" when nothing is chosen. */
    muted: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Expense | Income as one small switch (sits beside the amount). [type] is "EXPENSE" or "INCOME". */
@Composable
fun TypeToggle(type: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    SegmentedRow(
        labels = listOf("Expense", "Income"),
        selectedIndex = if (type == "INCOME") 1 else 0,
        onSelect = { onChange(if (it == 1) "INCOME" else "EXPENSE") },
        modifier = modifier
    )
}

/** A quiet link that turns the amount into a bill with several items. */
@Composable
fun AddItemsLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        Text(
            text = "Add items",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = scheme.primary,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
