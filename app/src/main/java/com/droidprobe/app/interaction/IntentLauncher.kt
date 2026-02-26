package com.droidprobe.app.interaction

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

class IntentLauncher(private val context: Context) {

    sealed class TypedExtra {
        data class StringExtra(val value: String) : TypedExtra()
        data class IntExtra(val value: Int) : TypedExtra()
        data class LongExtra(val value: Long) : TypedExtra()
        data class BooleanExtra(val value: Boolean) : TypedExtra()
        data class FloatExtra(val value: Float) : TypedExtra()
        data class DoubleExtra(val value: Double) : TypedExtra()
    }

    data class IntentParams(
        val action: String?,
        val data: Uri?,
        val type: String?,
        val componentPackage: String?,
        val componentClass: String?,
        val categories: List<String>,
        val extras: Map<String, TypedExtra>,
        val flags: Int
    )

    fun buildIntent(params: IntentParams): Intent {
        return Intent().apply {
            params.action?.takeIf { it.isNotBlank() }?.let { action = it }
            params.data?.let { data = it }
            params.type?.takeIf { it.isNotBlank() }?.let { type = it }
            if (!params.componentPackage.isNullOrBlank() && !params.componentClass.isNullOrBlank()) {
                component = ComponentName(params.componentPackage, params.componentClass)
            }
            params.categories.forEach { addCategory(it) }
            params.extras.forEach { (key, typed) ->
                when (typed) {
                    is TypedExtra.StringExtra -> putExtra(key, typed.value)
                    is TypedExtra.IntExtra -> putExtra(key, typed.value)
                    is TypedExtra.LongExtra -> putExtra(key, typed.value)
                    is TypedExtra.BooleanExtra -> putExtra(key, typed.value)
                    is TypedExtra.FloatExtra -> putExtra(key, typed.value)
                    is TypedExtra.DoubleExtra -> putExtra(key, typed.value)
                }
            }
            if (params.flags != 0) flags = params.flags
        }
    }

    fun launchActivity(intent: Intent): Result<Unit> {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun startService(intent: Intent): Result<ComponentName?> {
        return try {
            val result = context.startService(intent)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun sendBroadcast(intent: Intent): Result<Unit> {
        return try {
            context.sendBroadcast(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
