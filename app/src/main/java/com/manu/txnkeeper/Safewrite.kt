package com.manu.txnkeeper

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CancellationException

/**
 * Runs one database change without letting a failure crash the app.
 *
 * If it fails (phone storage full, database busy...) the user sees a short message and the screen
 * they were on stays as it was, so nothing they typed is lost. Returns true when the change was saved.
 *
 * Pass null for [what] to fail silently (background housekeeping nobody asked for).
 * Call it from a coroutine on the main thread (rememberCoroutineScope / LaunchedEffect): the message
 * is a Toast, which must be shown from there.
 */
suspend fun safeWrite(context: Context, what: String?, block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e   // the screen closed while saving: let the coroutine stop normally
    } catch (e: Exception) {
        if (what != null) {
            Toast.makeText(context, "Couldn't $what. Please try again.", Toast.LENGTH_LONG).show()
        }
        false
    }
