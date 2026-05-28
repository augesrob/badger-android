package com.badger.trucks.util

import com.badger.trucks.util.RemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Safe wrapper around scope.launch for UI network calls.
 * Catches any exception (network timeout, host unreachable, etc.) so it
 * never escapes to the main-thread uncaught exception handler and crashes
 * the process. The error is logged locally; the UI just stays as-is.
 */
fun CoroutineScope.safeLaunch(
    tag: String = "UI",
    block: suspend () -> Unit
) = launch {
    try {
        block()
    } catch (e: Exception) {
        RemoteLogger.w(tag, "safeLaunch caught: ${e::class.simpleName}: ${e.message}")
    }
}
