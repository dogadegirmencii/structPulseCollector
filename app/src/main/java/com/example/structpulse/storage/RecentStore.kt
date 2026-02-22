package com.example.structpulse.storage

import android.content.Context

class RecentStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = prefs.getString(KEY_RECENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun add(sessionId: String) {
        val current = load().toMutableList()

        // De-dup + add newest to top
        current.removeAll { it == sessionId }
        current.add(0, sessionId)

        // Keep only last 5
        val trimmed = current.take(MAX_RECENTS)

        prefs.edit()
            .putString(KEY_RECENTS, trimmed.joinToString(SEP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    companion object {
        private const val PREFS_NAME = "structpulse_prefs"
        private const val KEY_RECENTS = "recent_session_ids"
        private const val MAX_RECENTS = 5
        private const val SEP = "|"
    }
}
