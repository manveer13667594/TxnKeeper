package com.manu.txnkeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.TransactionTagCrossRef
import kotlinx.coroutines.delay

/** Longest tag name (short names keep the chips tidy). Room is left for an emoji at the start. */
const val MAX_TAG_NAME = 24

/**
 * Ready-made tags offered in the "New tag" dialog (name with its emoji, colour number). Tapping one
 * fills in the name and colour. Ones you already have are not shown again.
 */
val SUGGESTED_TAGS = listOf(
    "🛒 Groceries" to 0, "🍔 Food" to 1, "☕ Coffee" to 8, "🍕 Eating out" to 1,
    "🧾 Bills" to 2, "💡 Electricity" to 6, "📶 Internet" to 2, "📱 Recharge" to 5,
    "🏠 Rent" to 9, "🔧 Repairs" to 8, "🚗 Transport" to 7, "⛽ Fuel" to 8,
    "✈️ Travel" to 5, "🏨 Stay" to 5, "🎬 Entertainment" to 3, "🎮 Games" to 3,
    "🛍️ Shopping" to 4, "👕 Clothes" to 4, "🎁 Gifts" to 4, "💄 Beauty" to 4,
    "💊 Health" to 0, "🏋️ Fitness" to 0, "📚 Education" to 2, "👶 Kids" to 6,
    "🐶 Pets" to 1, "💰 Savings" to 6, "📈 Investment" to 0, "💼 Salary" to 0,
    "💸 Loan / EMI" to 7, "🛡️ Insurance" to 8, "🙏 Donation" to 3, "🎉 Party" to 3
)

/** The colours a tag can have. A tag stores only the number of its colour (TagEntity.colorIndex). */
val TAG_COLORS = listOf(
    Color(0xFF2E9E6B),   // green
    Color(0xFFE0833A),   // orange
    Color(0xFF3F7CE0),   // blue
    Color(0xFF8E5BD6),   // purple
    Color(0xFFD65B8A),   // pink
    Color(0xFF1FA3B5),   // teal
    Color(0xFFC9A227),   // gold
    Color(0xFFD9534F),   // red
    Color(0xFF6B7A8F),   // slate
    Color(0xFF7A9E2E)    // olive
)

fun tagColor(index: Int): Color = TAG_COLORS[index.mod(TAG_COLORS.size)]

/** Tidies a tag name: no extra spaces, not too long. A blank name stays blank. */
fun cleanTagName(raw: String): String {
    val spaced = raw.trim().replace(Regex("\\s+"), " ")
    var end = minOf(spaced.length, MAX_TAG_NAME)
    // Do not cut an emoji in half (an emoji can be two characters long)
    if (end < spaced.length && end > 0 && Character.isHighSurrogate(spaced[end - 1])) end--
    return spaced.substring(0, end).trim()
}

/**
 * Joins the tag list with the "transaction has tag" pairs: transaction id -> its tags (by name).
 * A transaction with no tags is simply not in the map.
 */
fun tagsByTransaction(tags: List<TagEntity>, links: List<TransactionTagCrossRef>): Map<Int, List<TagEntity>> {
    if (links.isEmpty()) return emptyMap()
    val byId = tags.associateBy { it.id }
    return links
        .mapNotNull { link -> byId[link.tagId]?.let { link.transactionId to it } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
}

/** A small coloured label, shown on a transaction row and in its details. */
@Composable
fun TagChip(tag: TagEntity, modifier: Modifier = Modifier) {
    val color = tagColor(tag.colorIndex)
    Text(
        text = tag.name,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * Compact one-line version for list rows: the first [maxVisible] tags, then "+N" for the rest.
 * Never wraps; a long tag name is shortened with "…" instead.
 */
@Composable
fun TagChipLine(tags: List<TagEntity>, modifier: Modifier = Modifier, maxVisible: Int = 2) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.take(maxVisible).forEach { TagChip(it, modifier = Modifier.weight(1f, fill = false)) }
        val more = tags.size - maxVisible
        if (more > 0) {
            Text(
                text = "+$more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** Several [TagChip]s side by side, wrapping onto the next line when they do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(tags: List<TagEntity>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tags.forEach { TagChip(it) }
    }
}

/**
 * The tag choice on the Add and Edit pages: every tag is a chip you tap to switch on or off, and
 * "+ New tag" at the end makes another one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(
    tags: List<TagEntity>,
    selected: Set<Long>,
    onChange: (Set<Long>) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // A block of up to 3 rows that scrolls sideways as one piece. Tags are dealt into the
        // currently shortest row, so the rows end up about the same length.
        val items: List<TagEntity?> = tags + listOf(null)          // null = the "+ New tag" chip
        val rowCount = when {
            items.size <= 4 -> 1
            items.size <= 8 -> 2
            else -> 3
        }
        val rows = List(rowCount) { mutableListOf<TagEntity?>() }
        val lengths = IntArray(rowCount)
        items.forEach { tag ->
            val label = tag?.name ?: "+ New tag"
            val target = lengths.indices.minByOrNull { lengths[it] } ?: 0
            rows[target].add(tag)
            lengths[target] += label.length + 4                       // + chip padding
        }
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rows.forEach { rowTags ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTags.forEach { tag ->
                        if (tag == null) {
                            ManuChip(label = "+ New tag", selected = false, onClick = onNew)
                        } else {
                            val on = tag.id in selected
                            ManuChip(
                                label = tag.name,
                                selected = on,
                                onClick = { onChange(if (on) selected - tag.id else selected + tag.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The "New tag" / "Edit tag" dialog: a name and a colour. The keyboard opens by itself.
 *
 * [takenNames] holds the other tags' names in lower case, to catch a name that is already used
 * (the name of the tag being edited is left out of it by the caller).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagDialog(
    initial: TagEntity?,
    takenNames: Set<String>,
    onSave: (name: String, colorIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var colorIndex by remember { mutableIntStateOf(initial?.colorIndex ?: 0) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // The words of the other tags without their emoji, so "Food" and "🍔 Food" count as the same tag
    val takenTexts = remember(takenNames) { takenNames.map { stripLeadingEmoji(it).lowercase() }.toSet() }

    // Put the cursor in the box (a short wait lets the dialog finish opening first)
    LaunchedEffect(Unit) {
        delay(150)
        try {
            focus.requestFocus()
        } catch (e: Exception) {
            // The user can still tap the box
        }
    }

    ManuDialog(
        visible = true,
        title = if (initial == null) "New tag" else "Edit tag",
        confirmText = if (initial == null) "Add" else "Save",
        onConfirm = {
            // Every tag is saved with an emoji: yours if you typed one, otherwise one that fits the words
            val clean = withTagEmoji(name, MAX_TAG_NAME)
            val words = stripLeadingEmoji(clean)
            when {
                words.isEmpty() -> error = "Type a name."
                words.lowercase() in takenTexts -> error = "You already have this tag."
                else -> onSave(clean, colorIndex)
            }
        },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManuTextField(
                value = name,
                onValueChange = {
                    if (it.length <= MAX_TAG_NAME) {
                        name = it
                        error = null
                    }
                },
                placeholder = { Text("e.g. 🛒 Groceries, 🏠 Rent") },
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // Shows the emoji that will be added when the name has none (or the shortened name)
            val preview = withTagEmoji(name, MAX_TAG_NAME)
            if (preview.isNotEmpty() && preview != name.trim()) {
                Text(
                    text = "Will be saved as $preview",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Quick picks: only when adding, and only the ones you do not have yet
            val suggestions = if (initial == null) {
                SUGGESTED_TAGS.filter { stripLeadingEmoji(it.first).lowercase() !in takenTexts }
            } else emptyList()
            if (suggestions.isNotEmpty()) {
                Text(
                    text = "Quick picks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { (label, color) ->
                            ManuChip(
                                label = label,
                                selected = name == label,
                                onClick = {
                                    name = label
                                    colorIndex = color
                                    error = null
                                }
                            )
                        }
                    }
                }
            }
            Text(
                text = "Colour",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TAG_COLORS.forEachIndexed { index, color ->
                    val chosen = index == colorIndex
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .semantics {
                                contentDescription = "Colour ${index + 1}"
                                selected = chosen
                            }
                            .clickable { colorIndex = index }
                    )
                }
            }
        }
    }
}