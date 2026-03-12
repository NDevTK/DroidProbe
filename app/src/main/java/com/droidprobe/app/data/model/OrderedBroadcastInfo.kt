package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

/**
 * Ordered broadcast result information detected in a BroadcastReceiver.
 * Captures resultCode, resultData, setResultExtras, and abortBroadcast() calls.
 */
@Serializable
data class OrderedBroadcastInfo(
    /** Whether the receiver sets resultCode (e.g. Activity.RESULT_OK) */
    val setsResultCode: Boolean = false,
    /** Whether the receiver sets resultData */
    val setsResultData: Boolean = false,
    /** Whether the receiver calls setResultExtras(Bundle) */
    val setsResultExtras: Boolean = false,
    /** Keys found in setResultExtras Bundle (via putString/putInt/etc.) */
    val resultExtrasKeys: List<String> = emptyList(),
    /** Whether the receiver calls abortBroadcast() */
    val abortsbroadcast: Boolean = false,
    /** The source class (receiver) in smali format */
    val sourceClass: String
)
