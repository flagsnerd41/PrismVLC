package com.example.myvlc

import android.content.SharedPreferences

/** Remembers where you stopped in each video (for resume and "Continue watching"). */
class ProgressStore(private val prefs: SharedPreferences) {

    data class Entry(val pos: Long, val dur: Long, val time: Long)

    fun save(uri: String, pos: Long, dur: Long) {
        if (dur <= 0L) return
        prefs.edit().putString("p_" + uri, pos.toString() + "|" + dur + "|" + System.currentTimeMillis()).apply()
    }

    fun remove(uri: String) {
        prefs.edit().remove("p_" + uri).apply()
    }

    fun get(uri: String): Entry? = parse(prefs.getString("p_" + uri, null))

    fun all(): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith("p_")) continue
            val entry = parse(value as? String) ?: continue
            result[key.substring(2)] = entry
        }
        return result
    }

    private fun parse(raw: String?): Entry? {
        if (raw == null) return null
        val parts = raw.split("|")
        if (parts.size < 3) return null
        return try {
            Entry(parts[0].toLong(), parts[1].toLong(), parts[2].toLong())
        } catch (e: Exception) {
            null
        }
    }
}
