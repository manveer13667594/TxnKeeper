package com.manu.txnkeeper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.manu.txnkeeper.data.local.AppDatabase
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.theme.ManuDesignSystem

/**
 * Full-screen Settings page (opened from the three-dot menu on the home screen).
 * Each group of settings is a [SettingsSection]; add new sections below "Data".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    database: AppDatabase,
    onManageAccounts: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    ManuScaffold(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "Settings",
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSection(title = "Appearance") {
                Text("Theme", style = MaterialTheme.typography.titleMedium)

                Text(
                    text = "System default follows your phone's light / dark setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        ManuChip(
                            label = mode.label,
                            selected = settings.themeMode == mode,
                            onClick = { settings.updateThemeMode(mode) }
                        )
                    }
                }
            }

            SettingsSection(title = "Accounts") {
                Text(
                    text = "Add, edit or delete accounts. The account chips on the home screen show up once you have more than one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ManuOutlineButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onManageAccounts
                ) { Text("Manage accounts") }
            }

            SettingsSection(title = "Data") {
                DataTransferControls(database = database)
            }

            // More sections (notifications, ...) go here later.
        }
    }
}

/** A small heading with a card under it that holds one group of settings. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ManuCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}