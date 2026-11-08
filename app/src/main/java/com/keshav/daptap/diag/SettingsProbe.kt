package com.keshav.daptap.diag

import android.content.Context
import android.provider.Settings

/**
 * Testing mode: lists the phone's settings whose names look like Samsung's Auto Blocker (or another switch that
 * turns debugging off), with their values. DapTap doesn't know Auto Blocker's key yet; one log from a phone with
 * it switched on, and one with it off, shows which key it is.
 */
object SettingsProbe {
    private val NAME = Regex("block|knox|maximum|protect|restrict|sideload|unknown_source", RegexOption.IGNORE_CASE)
    private const val MAX_HITS = 40

    fun find(context: Context): String = listOf(
        "global" to Settings.Global.CONTENT_URI,
        "secure" to Settings.Secure.CONTENT_URI,
        "system" to Settings.System.CONTENT_URI,
    ).joinToString(" · ") { (table, uri) ->
        runCatching {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                var total = 0
                val hits = mutableListOf<String>()
                while (c.moveToNext()) {
                    total++
                    val key = c.getString(0) ?: continue
                    if (hits.size < MAX_HITS && NAME.containsMatchIn(key)) hits += "$key=${c.getString(1)?.take(60)}"
                }
                "$table ($total readable): ${hits.joinToString().ifEmpty { "none" }}"
            } ?: "$table: no answer"
        }.getOrElse { "$table: ${it.javaClass.simpleName}: ${it.message?.take(100)}" }
    }
}
