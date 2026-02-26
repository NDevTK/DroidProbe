package com.droidprobe.app.analysis.dex

import android.util.Log

/**
 * Debug logger for DEX bytecode analysis.
 * Enable to trace how query parameter values and intent extra values
 * are extracted from bytecode — useful for diagnosing false positives
 * like error-message strings appearing as suggested values.
 *
 * Toggle at runtime via [enabled]. Logs use tag "DexDebug" and are
 * filtered to the currently set [filterClass] / [filterParam] if non-null.
 */
object DexDebugLog {

    /** Master switch — set to false to silence all DEX debug output. */
    var enabled: Boolean = false

    /**
     * Enable per-class "Processing class X" lines. Very noisy (thousands of lines).
     * Only useful when combined with [filterClass] to trace a single class.
     */
    var verbose: Boolean = false

    /**
     * Optional smali class filter (e.g. "Lcom/google/android/deskclock/HandleApiCalls;").
     * When non-null only log entries whose sourceClass contains this substring are emitted.
     */
    var filterClass: String? = null

    /**
     * Optional parameter/key filter (e.g. "timerStatus").
     * When non-null only log entries whose parameter or extra key matches are emitted.
     */
    var filterParam: String? = null

    private const val TAG = "DexDebug"

    fun log(msg: String) {
        if (!enabled) return
        Log.d(TAG, msg)
    }

    /** Verbose-only log — suppressed unless [verbose] is true. */
    fun logVerbose(sourceClass: String?, msg: String) {
        if (!enabled || !verbose) return
        if (filterClass != null && sourceClass != null && !sourceClass.contains(filterClass!!)) return
        Log.d(TAG, msg)
    }

    fun logFiltered(sourceClass: String?, paramOrKey: String?, msg: String) {
        if (!enabled) return
        if (filterClass != null && sourceClass != null && !sourceClass.contains(filterClass!!)) return
        if (filterParam != null && paramOrKey != null && paramOrKey != filterParam) return
        Log.d(TAG, msg)
    }
}
