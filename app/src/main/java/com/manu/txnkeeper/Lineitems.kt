package com.manu.txnkeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
 * The bill. One card per item: a number badge and the name on top, then a quantity with - / + buttons,
 * the price of one, and what the line adds up to. "Add item" puts a new card at the end and jumps
 * straight into its name; the total sits at the bottom. Emptying the list (the cross on the last item,
 * or "Clear") goes back to typing the amount by hand.
 */
@Composable
fun ItemsEditor(
    items: List<ItemDraft>,
    onChange: (List<ItemDraft>) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // The item whose name box should take the keyboard (a fresh one, or the first when the editor opens)
    var focusKey by remember {
        mutableStateOf(items.firstOrNull()?.takeIf { it.name.isEmpty() && it.price.isEmpty() }?.key)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.primary.copy(alpha = 0.06f))
            .border(1.dp, scheme.primary.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Title, how many, and a way out
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Bill items",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            val count = items.count { it.totalPaise() != null }
            if (count > 0) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(CircleShape)
                        .background(scheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onChange(emptyList()) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        items.forEachIndexed { index, item ->
            ItemCard(
                number = index + 1,
                item = item,
                focus = focusKey == item.key,
                onFocused = { focusKey = null },
                onChange = { new -> onChange(items.map { if (it.key == item.key) new else it }) },
                onRemove = { onChange(items.filter { it.key != item.key }) }
            )
        }

        // Add another line
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.primary.copy(alpha = 0.10f))
                .clickable {
                    val fresh = ItemDraft()
                    focusKey = fresh.key
                    onChange(items + fresh)
                }
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Text(
                text = "Add item",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        ItemsTotalRow(items)
    }
}

/** One line of the bill as a small card. */
@Composable
private fun ItemCard(
    number: Int,
    item: ItemDraft,
    focus: Boolean,
    onFocused: () -> Unit,
    onChange: (ItemDraft) -> Unit,
    onRemove: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            runCatching { nameFocus.requestFocus() }
            onFocused()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$number", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = scheme.primary
                )
            }
            CompactField(
                value = item.name,
                onValueChange = { onChange(item.copy(name = it)) },
                hint = "Item name",
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .focusRequester(nameFocus),
                keyboardType = KeyboardType.Text,
                textStyle = MaterialTheme.typography.bodyLarge
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove item",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(7.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QtyStepper(item = item, onChange = onChange)
            Text("×", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            CompactField(
                value = item.price,
                onValueChange = { onChange(item.copy(price = it)) },
                hint = "Price",
                prefix = "₹",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Decimal
            )
            Text(
                text = item.totalPaise()?.let { formatMoney(it) } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (item.totalPaise() != null) scheme.onSurface else scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(68.dp)
            )
        }
    }
}

/** Quantity with a minus and a plus button, and a box you can also type in (for 1.5 kg). */
@Composable
private fun QtyStepper(item: ItemDraft, onChange: (ItemDraft) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    fun step(delta: Int) {
        val q = item.qtyValue() ?: 1.0
        val next = if (delta < 0) (q - 1).takeIf { it >= 1 } ?: q else q + 1
        onChange(item.copy(qty = formatQuantity(next)))
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.onSurface.copy(alpha = 0.06f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Remove, contentDescription = "Less",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier
                .size(34.dp)
                .clickable { step(-1) }
                .padding(9.dp)
        )
        CompactField(
            value = item.qty,
            onValueChange = { onChange(item.copy(qty = it)) },
            hint = "1",
            modifier = Modifier.width(34.dp),
            keyboardType = KeyboardType.Decimal,
            centered = true,
            boxed = false
        )
        Icon(
            Icons.Default.Add, contentDescription = "More",
            tint = scheme.primary,
            modifier = Modifier
                .size(34.dp)
                .clickable { step(+1) }
                .padding(9.dp)
        )
    }
}

/** A small single-line text box: tinted background, hint, optional prefix such as the rupee sign. */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    centered: Boolean = false,
    boxed: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val scheme = MaterialTheme.colorScheme
    val style = textStyle.copy(
        color = scheme.onSurface,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        decorationBox = { inner ->
            Row(
                modifier = (if (boxed) Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.onSurface.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 9.dp) else Modifier.padding(vertical = 9.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (prefix != null) {
                    Text(
                        prefix, style = style.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Box(Modifier.weight(1f, fill = false), contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(hint, style = style, color = scheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1)
                    }
                    inner()
                }
            }
        }
    )
}

/** "Total ₹X · N items": the amount the transaction will be saved with. */
@Composable
fun ItemsTotalRow(items: List<ItemDraft>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val count = items.count { it.totalPaise() != null }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Total", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (count == 0) "Fill a price to count an item" else "$count ${if (count == 1) "item" else "items"}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
        }
        Text(
            text = formatMoney(items.validTotalPaise()),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.primary
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