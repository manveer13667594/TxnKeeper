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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.manu.manudesignsystem.components.ManuButton
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
        answer = "Tap + on the home screen. Enter a title and amount, choose Expense or Income, then add any other details.\n" +
                "Save & next lets you add another. Save & exit takes you back."
    ),
    Faq(
        question = "How do I scan a receipt or payment screenshot?",
        answer = "Swipe from left to right on the home screen, then take a photo or pick an image. You can also share an image to TxnKeeper from another app.\n" +
                "TxnKeeper fills in the details it can find. Check them before you save.\n" +
                "To copy a saved receipt to your gallery, open its image and tap Save to gallery."
    ),
    Faq(
        question = "How does voice entry work?",
        answer = "Swipe from right to left on the home screen, or tap the mic on the Add page. Say something like \"paid 500 to Rahul, got 2000 salary\".\n" +
                "Check the details before you save.\n" +
                "Voice files (about 57 MB) download automatically using Wi-Fi or mobile data. A notification shows the progress if notifications are allowed.\n" +
                "Until then, voice uses your phone's speech service if Fast voice entry is on. This may need internet.\n" +
                "After the download, voice entry can work offline. Some phones can't use offline voice and always need Fast voice entry.\n" +
                "Fast voice entry may send audio online; you can turn it off in Settings, then Defaults."
    ),
    Faq(
        question = "What if I say more than one transaction?",
        answer = "Use the arrows to check each entry. Untick any you don't want, then tap Save & exit to save the ticked ones."
    ),
    Faq(
        question = "How do I view, edit, share or delete a transaction?",
        answer = "Tap a transaction to see its details. Use the buttons at the top to share, edit or delete it.\n" +
                "You can share it as text, PDF or CSV. Delete asks you to confirm."
    ),
    Faq(
        question = "How do I select several transactions?",
        answer = "Touch and hold a transaction, then tap others to add them. Touch and hold a date to select that whole day.\n" +
                "Use the buttons at the side to share, edit, move, tag, change the payment method or delete."
    ),
    Faq(
        question = "How do I search or filter?",
        answer = "Tap the search icon and type a title, amount, date, time or payment method, for example \"evening\" or \"upi\".\n" +
                "Tap the filter icon to narrow the list. The totals follow your search and filters."
    ),
    Faq(
        question = "How do I add home screen widgets?",
        answer = "Open ⋮, then Settings, then Home screen widgets, and tap Add to home screen.\n" +
                "You can also touch and hold your home screen, tap Widgets and find TxnKeeper. Touch and hold a widget to resize it."
    ),
    Faq(
        question = "How do I manage accounts?",
        answer = "Open ⋮, then Settings, then Accounts. Tap an account to edit it, or use its ⋮ to delete it.\n" +
                "Deleting an account also deletes its transactions and their images. To confirm, type delete account. The last account can't be deleted.\n" +
                "With more than one account, use the chips on the home screen to switch between them."
    ),
    Faq(
        question = "How do I add or remove payment methods?",
        answer = "Open the payment method list while adding or editing a transaction, then tap + Add new.\n" +
                "To remove one, open ⋮, then Settings, then Payment methods, and tap its X. Past transactions keep their label."
    ),
    Faq(
        question = "How do I export my data?",
        answer = "Open ⋮, then Settings, then Export & import. Choose what to export, then export it as CSV or PDF. With Include images on, CSV becomes a ZIP that holds your images.\n" +
                "Pick where to save the file. When it's done, you can open or share it. Last export shows the newest file and where it was saved."
    ),
    Faq(
        question = "How do I import my data?",
        answer = "Open ⋮, then Settings, then Export & import, and tap Import from CSV or ZIP. Use a CSV or a ZIP made by TxnKeeper. A ZIP brings back images too.\n" +
                "Opening balances are not included, so set them again in Accounts if needed."
    ),
    Faq(
        question = "Where is my data stored?",
        answer = "Your transactions and receipt images are saved on your phone. Export a CSV now and then as a backup, or a ZIP if you have images. Keep it somewhere safe."
    ),
    Faq(
        question = "How do I delete my data?",
        answer = "Open ⋮, then Settings, then Delete data. Choose All data or one account, then type the phrase shown to confirm.\n" +
                "This can't be undone, so export a copy first if you need one. Exported files and images saved to your gallery are not deleted."
    ),
    Faq(
        question = "Can I see the tutorial again?",
        answer = "Tap Show tutorial at the top of this page or in Settings."
    ),
)

/**
 * Full-screen Help page (opened from the three-dot menu on the home screen).
 * A list of questions: tap one to show its answer, tap it again to hide it.
 * Only one answer is open at a time.
 */
@Composable
fun HelpScreen(
    onClose: () -> Unit,
    onShowTutorial: () -> Unit,
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
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
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
            ManuButton(modifier = Modifier.fillMaxWidth(), onClick = onShowTutorial) {
                Text("Show tutorial")
            }
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