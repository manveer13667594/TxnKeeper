package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.theme.ManuDesignSystem

/** One question and its answer on the Help page. */
private class Faq(val question: String, val answer: String)

/**
 * The questions, in the order they appear. To change the Help text, edit this list only.
 * Use "\n" inside an answer to start a new line.
 */
private val FAQS = listOf(
    Faq(
        question = "How do I add a transaction?",
        answer = "Tap the + button on the home screen. Enter a title and amount, choose Expense or Income, " +
                "and add a description if you like.\n" +
                "Save & next keeps the page open for another entry. Save & exit goes back to the list."
    ),
    Faq(
        question = "How do I scan a receipt or payment screenshot?",
        answer = "Tap the camera icon at the top of the home screen, or swipe from left to right. " +
                "Take a photo or pick an image. You can also share an image to TxnKeeper from your gallery or a payment app.\n" +
                "The amount, date, time, type and title are filled in for you. Check the form before you save."
    ),
    Faq(
        question = "How does voice entry work?",
        answer = "On the Add page, tap the mic button and say one or more transactions, " +
                "like \"paid 500 to Rahul, got 2000 salary\". Tap it again to stop.\n" +
                "The first time, a speech model is downloaded, so you need internet that once. After that it works offline."
    ),
    Faq(
        question = "How do I manage accounts?",
        answer = "Open the ⋮ menu, then Settings, then Manage accounts. You can add, edit or delete accounts.\n" +
                "With more than one account, chips under the title let you switch between All and a single account. " +
                "Deleting an account also deletes its transactions."
    ),
    Faq(
        question = "How do I add or remove payment methods?",
        answer = "When adding or editing a transaction, open the payment method dropdown and tap + Add new. " +
                "Type a name and tap Add.\n" +
                "To remove one, open the ⋮ menu, then Settings, then Payment methods, and tap the X next to it. " +
                "Transactions that already use it keep their label."
    ),
    Faq(
        question = "How do I search or filter?",
        answer = "Tap the magnifier to search by title, amount, date or time of day, for example \"evening\".\n" +
                "Open the ⋮ menu, then Filters, to narrow the list by account, payment method, amount or dates. " +
                "The totals at the top follow your search and filters."
    ),
    Faq(
        question = "How do I edit or delete a transaction?",
        answer = "Tap a transaction to edit it. The ⋮ on each row also has Edit and Delete.\n" +
                "Delete asks first, and it can't be undone."
    ),
    Faq(
        question = "How do I export or import my data?",
        answer = "Open the ⋮ menu, then Settings, then Data.\n" +
                "Export as CSV or PDF: everything, a date range, or the latest transactions. " +
                "You can narrow it down by account, payment method, income or expense, and an amount range.\n" +
                "Import reads a CSV file, and it is all or nothing.\n" +
                "Opening balances are not in the CSV, so set them again in Accounts if needed."
    ),
    Faq(
        question = "Where is my data stored?",
        answer = "On your phone. Export a CSV from Settings now and then to keep a backup."
    ),
    Faq(
        question = "How do I delete my data?",
        answer = "Open the ⋮ menu, then Settings, then Delete data. Choose All data or a single account, " +
                "then type the phrase shown to confirm.\n" +
                "This can't be undone, so export a CSV first if you want a copy. " +
                "Photos taken with the in-app camera are saved in your gallery and are not deleted."
    ),
    Faq(
        question = "How do I delete my data?",
        answer = "Open the ⋮ menu, then Settings, then Delete data. Choose All data, or one account " +
                "(you need at least two accounts for that).\n" +
                "Then type the phrase shown to confirm. Deleting can't be undone, so export a CSV first if you want a copy."
    )
)

/**
 * Full-screen Help page (opened from the three-dot menu on the home screen).
 * A list of questions: tap one to show its answer, tap it again to hide it.
 * Only one answer is open at a time.
 */
@Composable
fun HelpScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Which question is open (null = all closed)
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    ManuScaffold(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "Help",
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.Default.ArrowBack,
                onNavigationClick = onClose
            )
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FAQS.forEachIndexed { index, faq ->
                FaqItem(
                    faq = faq,
                    expanded = openIndex == index,
                    onToggle = { openIndex = if (openIndex == index) null else index }
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** A card with the question and an up / down arrow; the answer opens underneath. */
@Composable
private fun FaqItem(
    faq: Faq,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = if (expanded) "Hide answer" else "Show answer",
                    role = Role.Button,
                    onClick = onToggle
                )
                .padding(vertical = ManuDesignSystem.spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = faq.question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = faq.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}