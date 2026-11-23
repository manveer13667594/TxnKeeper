package com.manu.txnkeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.theme.ManuDesignSystem
import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.TransactionEntity
import com.manu.txnkeeper.ui.theme.ChartOtherColor
import com.manu.txnkeeper.ui.theme.ExpenseColor
import com.manu.txnkeeper.ui.theme.IncomeColor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DAY_MS = 86_400_000L
private const val HEATMAP_DAYS = 35

/** Everything the three insight cards need, worked out once per window. */
private class Habits(
    val spent: Long,
    val noSpendDays: Int,
    val heatDays: Int,
    val lastDay: LocalDate,
    val perDay: Map<LocalDate, Long>,
    val busiestDay: String?,
    val split: List<SplitPart>,
    val bigThings: List<Pair<String, Pair<String, String>>>   // label -> (title, amount)
)

private class SplitPart(val name: String, val paise: Long, val color: Color)

/**
 * Three visual cards instead of a grid of boxes: a calendar of daily spending (the no-spend days
 * show up as gaps), a single bar showing where the money went, and a "this period vs the one before"
 * comparison. Tap a day to see what was spent on it.
 */
@Composable
internal fun SmartInsights(
    txns: List<TransactionEntity>,
    days: Int,
    windowLabel: String,
    previousExpense: Long?,
    base: ChartInsights?,
    tagsOf: Map<Int, List<TagEntity>>,
    modifier: Modifier = Modifier
) {
    val otherColor = ChartOtherColor
    val tagColors = tagsOf.values.flatten().distinctBy { it.id }.associate { it.id to tagColor(it.colorIndex) }
    val habits = remember(txns, days, base, tagsOf, otherColor) {
        computeHabits(txns, days, base, tagsOf, tagColors, otherColor)
    } ?: return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Insights · $windowLabel",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (habits.spent > 0) {
            ComparisonCard(habits.spent, previousExpense)
            DayCalendarCard(habits)
            if (habits.split.isNotEmpty()) SplitCard(habits)
            if (habits.bigThings.isNotEmpty()) BigThingsCard(habits.bigThings)
        } else {
            Text(
                text = "Nothing was spent in this window.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ card 1: this vs before

@Composable
private fun ComparisonCard(spent: Long, before: Long?) {
    val scheme = MaterialTheme.colorScheme
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
            if (before == null || before <= 0L) {
                Text("You spent", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(
                    formatMoney(spent), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = ExpenseColor
                )
                Text(
                    "Nothing earlier to compare with yet.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
                )
                return@Column
            }
            val diff = spent - before
            val less = diff <= 0
            Text(
                text = if (diff == 0L) "Same as before" else if (less) "You spent less" else "You spent more",
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatMoney(abs(diff)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (less) IncomeColor else ExpenseColor
                )
                Text(
                    text = "  ${if (less) "▼" else "▲"} ${abs(((diff) * 100.0 / before).roundToInt())}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (less) IncomeColor else ExpenseColor,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            val top = maxOf(spent, before).toFloat()
            CompareBar("Now", spent, spent / top, ExpenseColor)
            CompareBar("Before", before, before / top, scheme.onSurface.copy(alpha = 0.28f))
        }
    }
}

@Composable
private fun CompareBar(label: String, paise: Long, fraction: Float, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp)
        )
        Box(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(
            formatMoney(paise), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp), maxLines = 1
        )
    }
}

// ------------------------------------------------------------------ card 2: the calendar

@Composable
private fun DayCalendarCard(h: Habits) {
    val scheme = MaterialTheme.colorScheme
    var picked by remember(h) { mutableStateOf<LocalDate?>(null) }
    val maxDay = (h.perDay.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    val first = h.lastDay.minusDays((h.heatDays - 1).toLong())
    // Weeks run Monday to Sunday; start on the Monday on or before the first day
    val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val weeks = ((h.lastDay.toEpochDay() - gridStart.toEpochDay()) / 7 + 1).toInt()
    val fmt = remember { DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()) }

    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (h.noSpendDays == 0) "No free days" else "${h.noSpendDays} no-spend ${if (h.noSpendDays == 1) "day" else "days"}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (h.noSpendDays > 0) IncomeColor else scheme.onSurface
                    )
                    Text(
                        text = "in the last ${h.heatDays} days" + (h.busiestDay?.let { " · most on ${it}s" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant
                    )
                }
            }
            // Weekday letters
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach {
                    Text(
                        it.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                }
            }
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (w in 0 until weeks) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (d in 0 until 7) {
                            val date = gridStart.plusDays((w * 7 + d).toLong())
                            val inRange = !date.isBefore(first) && !date.isAfter(h.lastDay)
                            val amount = h.perDay[date] ?: 0L
                            val fill = when {
                                !inRange -> Color.Transparent
                                amount == 0L -> IncomeColor.copy(alpha = 0.16f)
                                else -> ExpenseColor.copy(alpha = 0.22f + 0.78f * (amount.toFloat() / maxDay))
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(fill)
                                    .then(
                                        if (picked == date) Modifier.border(1.5.dp, scheme.onSurface, RoundedCornerShape(7.dp))
                                        else Modifier
                                    )
                                    .then(if (inRange) Modifier.clickable { picked = if (picked == date) null else date } else Modifier)
                            )
                        }
                    }
                }
            }
            val p = picked
            Text(
                text = if (p == null) "Tap a day" else
                    "${p.format(fmt)} · " + ((h.perDay[p] ?: 0L).let { if (it == 0L) "nothing spent" else formatMoney(it) }),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ card 3: where it went

@Composable
private fun SplitCard(h: Habits) {
    val scheme = MaterialTheme.colorScheme
    val total = h.split.sumOf { it.paise }.coerceAtLeast(1L)
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
            Text("Where it went", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // One bar, cut into the biggest tags
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(14.dp)
                    .clip(CircleShape),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                h.split.forEach {
                    Box(
                        Modifier
                            .weight(it.paise.toFloat().coerceAtLeast(1f))
                            .height(14.dp)
                            .background(it.color)
                    )
                }
            }
            h.split.forEach {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(it.color))
                    Text(
                        it.name, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatMoney(it.paise), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${(it.paise * 100.0 / total).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.End, modifier = Modifier.width(42.dp)
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ card 4: the big ones

@Composable
private fun BigThingsCard(items: List<Pair<String, Pair<String, String>>>) {
    val scheme = MaterialTheme.colorScheme
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
            items.forEachIndexed { i, (label, v) ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Text(
                            v.first, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        v.second, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the numbers

private fun computeHabits(
    all: List<TransactionEntity>,
    days: Int,
    base: ChartInsights?,
    tagsOf: Map<Int, List<TagEntity>>,
    tagColors: Map<Long, Color>,
    otherColor: Color
): Habits? {
    if (all.isEmpty()) return null
    val zone = ZoneId.systemDefault()
    val w = withinDays(all, days)
    val expenses = w.filter { it.type == "EXPENSE" }
    val spent = expenses.sumOf { it.amountPaise }

    fun dayOf(t: TransactionEntity): LocalDate = Instant.ofEpochMilli(t.dateTime).atZone(zone).toLocalDate()
    val lastDay = dayOf(all.maxByOrNull { it.dateTime }!!)
    val firstDay = dayOf(all.minByOrNull { it.dateTime }!!)
    val spanAll = (lastDay.toEpochDay() - firstDay.toEpochDay()).toInt() + 1
    val heatDays = (if (days in 1..HEATMAP_DAYS) days else HEATMAP_DAYS).coerceAtMost(spanAll).coerceAtLeast(1)

    // Spending per calendar day, over the days the calendar shows
    val calFirst = lastDay.minusDays((heatDays - 1).toLong())
    val perDay = HashMap<LocalDate, Long>()
    for (t in all) {
        if (t.type != "EXPENSE") continue
        val d = dayOf(t)
        if (d.isBefore(calFirst) || d.isAfter(lastDay)) continue
        perDay[d] = (perDay[d] ?: 0L) + t.amountPaise
    }
    val noSpend = heatDays - perDay.size

    val weekdayTotals = spendByWeekday(w, "EXPENSE")
    val busiest = if (heatDays >= 7) busiestWeekday(weekdayTotals)?.let {
        DayOfWeek.of(it + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())
    } else null

    // Biggest tags as parts of one bar: the top four, the rest as "Other"
    val byTag = LinkedHashMap<TagEntity, Long>()
    var untagged = 0L
    for (t in expenses) {
        val tg = tagsOf[t.id]?.firstOrNull()
        if (tg == null) untagged += t.amountPaise else byTag[tg] = (byTag[tg] ?: 0L) + t.amountPaise
    }
    val sorted = byTag.entries.sortedByDescending { it.value }
    val split = ArrayList<SplitPart>()
    if (sorted.isNotEmpty()) {
        sorted.take(4).forEach { split += SplitPart(it.key.name, it.value, tagColors[it.key.id] ?: otherColor) }
        val rest = sorted.drop(4).sumOf { it.value } + untagged
        if (rest > 0) split += SplitPart(if (sorted.size > 4) "Everything else" else "Untagged", rest, otherColor)
    }

    // The big one and the habit
    val things = ArrayList<Pair<String, Pair<String, String>>>()
    base?.biggestExpense?.let {
        things += "Biggest expense" to (it.txn.title.ifBlank { formatDateLong(it.txn.dateTime) } to formatMoney(it.txn.amountPaise))
    }
    expenses.filter { it.title.isNotBlank() }
        .groupBy { it.title.trim().lowercase() }
        .filter { it.value.size >= 3 }
        .maxByOrNull { e -> e.value.sumOf { it.amountPaise } }
        ?.let { (_, list) ->
            things += "You keep buying" to
                    ("${list.first().title.trim()} · ${list.size} times" to formatMoney(list.sumOf { it.amountPaise }))
        }

    return Habits(spent, noSpend, heatDays, lastDay, perDay, busiest, split, things)
}