package com.manu.txnkeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.txnkeeper.data.local.TransactionItemEntity

// ------------------------------------------------------------------ what is typed

private val draftKeys = java.util.concurrent.atomic.AtomicLong()
private fun nextDraftKey(): Long = draftKeys.incrementAndGet()

/** One line of a bill while it is being typed: everything is text until it is saved. */
data class ItemDraft(
    /** Only to tell lines apart while the list changes. */
    val key: Long = nextDraftKey(),
    val name: String = "",
    val qty: String = "1",
    val price: String = ""
)

/** How many; an empty box means 1. Null when it is not a positive number. */
fun ItemDraft.qtyValue(): Double? =
    qty.trim().ifEmpty { "1" }.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

/** The price of one, in paise; null when it is empty or not a positive number. */
fun ItemDraft.unitPaise(): Long? = parsePaise(price.replace(",", "").trim())?.takeIf { it > 0 }

/** What the line adds up to, or null while it is not complete. */
fun ItemDraft.totalPaise(): Long? {
    val q = qtyValue() ?: return null
    val p = unitPaise() ?: return null
    return Math.round(q * p)
}

/** The sum of every complete line. */
fun List<ItemDraft>.validTotalPaise(): Long = sumOf { it.totalPaise() ?: 0L }

/** True when at least one line is complete (then the bill decides the transaction's amount). */
fun List<ItemDraft>.hasValidItems(): Boolean = any { it.totalPaise() != null }

/** The lines to save: incomplete ones are left out, a missing name becomes "Item 1", "Item 2"... */
fun List<ItemDraft>.toEntities(): List<TransactionItemEntity> =
    filter { it.totalPaise() != null }.mapIndexed { i, d ->
        TransactionItemEntity(
            transactionId = 0,
            name = d.name.trim().ifBlank { "Item ${i + 1}" },
            quantity = d.qtyValue()!!,
            unitPricePaise = d.unitPaise()!!,
            position = i
        )
    }

fun List<TransactionItemEntity>.toDrafts(): List<ItemDraft> =
    map { ItemDraft(name = it.name, qty = formatQuantity(it.quantity), price = formatAmountPaise(it.unitPricePaise)) }

/** 2.0 -> "2", 1.5 -> "1.5". */
fun formatQuantity(q: Double): String =
    if (q == Math.rint(q) && q < 1e9) q.toLong().toString() else q.toBigDecimal().stripTrailingZeros().toPlainString()

// ------------------------------------------------------------------ the editor

/**
 * The bill: one block per item (name, then quantity x price and the line total), "Add item", and the
 * total at the bottom. Emptying the list (the cross on the last item, or "Remove items") goes back to
 * typing the amount by hand.
 */
@Composable
fun ItemsEditor(
    items: List<ItemDraft>,
    onChange: (List<ItemDraft>) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Items",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onChange(emptyList()) }) { Text("Remove items") }
        }

        items.forEachIndexed { index, item ->
            fun change(new: ItemDraft) = onChange(items.map { if (it.key == item.key) new else it })
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ManuTextField(
                        value = item.name,
                        onValueChange = { change(item.copy(name = it)) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Item ${index + 1}") }
                    )
                    IconButton(onClick = { onChange(items.filter { it.key != item.key }) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove item")
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ManuTextField(
                        value = item.qty,
                        onValueChange = { change(item.copy(qty = it)) },
                        modifier = Modifier.weight(0.8f),
                        placeholder = { Text("Qty") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text("×", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                    ManuTextField(
                        value = item.price,
                        onValueChange = { change(item.copy(price = it)) },
                        modifier = Modifier.weight(1.2f),
                        placeholder = { Text("Price") },
                        leadingContent = { Text("₹", fontWeight = FontWeight.Bold) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text(
                        text = item.totalPaise()?.let { formatMoney(it) } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(72.dp)
                    )
                }
            }
            if (index < items.lastIndex) HorizontalDivider()
        }

        TextButton(onClick = { onChange(items + ItemDraft()) }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("  Add item")
        }
        HorizontalDivider()
        ItemsTotalRow(items)
    }
}

/** "Total ₹X · N items": the amount the transaction will be saved with. */
@Composable
fun ItemsTotalRow(items: List<ItemDraft>, modifier: Modifier = Modifier) {
    val count = items.count { it.totalPaise() != null }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Total", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (count == 0) "Fill a price to count an item" else "$count ${if (count == 1) "item" else "items"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatMoney(items.validTotalPaise()),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

// ------------------------------------------------------------------ read-only list (details)

/** The saved items of a transaction: name, "2 × ₹30" under it, the line total on the right. */
@Composable
fun ItemsList(items: List<TransactionItemEntity>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { i, it ->
            if (i > 0) HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(it.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${formatQuantity(it.quantity)} × ${formatMoney(it.unitPricePaise)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    formatMoney(it.totalPaise),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
