package com.manu.txnkeeper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuTextField
import kotlinx.coroutines.delay

/** Longest payment method name (short names keep the chips and the list tidy). */
const val MAX_PAYMENT_METHOD_NAME = 20

/**
 * Tidies a payment method name the same way everywhere: "  bank   transfer " becomes
 * "BANK TRANSFER". A blank name stays blank.
 */
fun cleanPaymentMethodName(raw: String): String =
    raw.trim().replace(Regex("\\s+"), " ").uppercase().take(MAX_PAYMENT_METHOD_NAME).trim()

/**
 * The small "new payment method" dialog, used by the Settings page and by the payment dropdown
 * on the Add / Edit pages. The keyboard opens by itself, so it is: type a name, tap Add.
 *
 * [existing] is the current list, to catch a name that is already there.
 */
@Composable
fun AddPaymentMethodDialog(
    existing: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

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
        title = "New payment method",
        confirmText = "Add",
        onConfirm = {
            val clean = cleanPaymentMethodName(name)
            when {
                clean.isEmpty() -> error = "Type a name."
                clean in existing -> error = "You already have this one."
                else -> onAdd(clean)
            }
        },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManuTextField(
                value = name,
                onValueChange = {
                    if (it.length <= MAX_PAYMENT_METHOD_NAME) {
                        name = it
                        error = null
                    }
                },
                placeholder = { Text("e.g. Wallet, Cheque") },
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}