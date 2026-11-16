package com.manu.txnkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Black-and-white palette (light and dark), taken from the two design samples.
// Light: white / soft grey surfaces, near-black text and buttons.
// Dark : pure black, charcoal cards, near-white text and buttons.

// ---- Light
val LightBackground = Color(0xFFF4F4F6)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEEFF1)
val LightOnSurface = Color(0xFF0B0B0C)
val LightOnSurfaceVariant = Color(0xFF6B6F76)
val LightOutline = Color(0xFFC9CBD0)
val LightOutlineVariant = Color(0xFFE3E4E7)
val LightInk = Color(0xFF0E0E10)           // buttons / selected pills (near black)

// ---- Dark
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1A1A1C)
val DarkSurfaceVariant = Color(0xFF242426)
val DarkOnSurface = Color(0xFFF2F2F4)
val DarkOnSurfaceVariant = Color(0xFFA0A0A6)
val DarkOutline = Color(0xFF48484C)
val DarkOutlineVariant = Color(0xFF2E2E31)
val DarkInk = Color(0xFFF2F2F4)            // buttons / selected pills (near white)

// ---- Error (same in both)
val ErrorLight = Color(0xFFC62828)
val ErrorDark = Color(0xFFFF6B6B)

// ---- Income / expense (Light Theme over white / light gray surfaces)
val IncomeColorLight = Color(0xFF0A7F3B)   // Deep emerald — 5.11:1 contrast on white
val ExpenseColorLight = Color(0xFFD51515)  // Rich crimson — 5.32:1 contrast on white

// ---- Income / expense (Dark Theme over pure black / dark gray surfaces)
val IncomeColorDark = Color(0xFF3EDA7F)    // Soft mint-green — 11.54:1 on black
val ExpenseColorDark = Color(0xFFF58080)   // Warm coral-red — 8.28:1 on black

// ---- Dynamic Income / Expense (theme-aware based on active MaterialTheme background luminance)
val IncomeColor: Color
    @Composable
    get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) IncomeColorDark else IncomeColorLight

val ExpenseColor: Color
    @Composable
    get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) ExpenseColorDark else ExpenseColorLight

// ---- Chart categories (pie slices in Stats > Breakdown). Handed out in this order, biggest slice first.
// No green or red here, so a slice is never mistaken for income or expense.
val ChartCategoryColors = listOf(
    Color(0xFF3D7EDB),   // blue
    Color(0xFFE69F00),   // amber
    Color(0xFF9B5DE5),   // violet
    Color(0xFF2AA6A0),   // teal
    Color(0xFFE0709F),   // pink
    Color(0xFF8D6E4F),   // brown
    Color(0xFF56B4E9)    // sky
)
val ChartOtherColor = Color(0xFF8E8E93)   // the grouped "Other" slice
