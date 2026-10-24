package com.example.txnkeeper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

val PAYMENT_METHODS = listOf("CASH", "UPI", "CARD", "BANK TRANSFER")

/** Kinds of account shown as chips when adding or editing an account. */
val ACCOUNT_TYPES = listOf("BANK", "CASH", "WALLET", "CARD")

/**
 * Icons that are NOT in the default icon set (they live in material-icons-extended).
 * Drawn from path data here, so you don't have to add that big dependency.
 */
object AppIcons {
    val FilterList: ImageVector by lazy {
        icon("FilterList", "M10,18h4v-2h-4v2zM3,6v2h18V6H3zM6,13h12v-2H6v2z")
    }

    val CameraAlt: ImageVector by lazy {
        icon(
            "CameraAlt",
            "M12,8.8a3.2,3.2 0,1 0,0 6.4a3.2,3.2 0,1 0,0 -6.4z " +
                    "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6" +
                    "c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9z " +
                    "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z"
        )
    }

    /** Wallet: the payment-method field. */
    val Wallet: ImageVector by lazy {
        icon(
            "Wallet",
            "M21,18v1c0,1.1 -0.9,2 -2,2H5c-1.11,0 -2,-0.9 -2,-2V5c0,-1.1 0.89,-2 2,-2h14c1.1,0 2,0.9 2,2v1h-9" +
                    "c-1.11,0 -2,0.9 -2,2v8c0,1.1 0.89,2 2,2h9z M12,16h10V8H12v8z " +
                    "M16,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"
        )
    }

    /** Document with lines: the title field. */
    val Description: ImageVector by lazy {
        icon(
            "Description",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6z " +
                    "M16,18H8v-2h8v2z M16,14H8v-2h8v2z M13,9V3.5L18.5,9H13z"
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black)
        ).build()
}