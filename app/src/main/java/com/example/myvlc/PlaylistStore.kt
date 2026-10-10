package com.example.myvlc

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Saves your own playlists (name -> list of video links) on the phone. */
class PlaylistStore(private val prefs: SharedPreferences) {

    private val map = LinkedHashMap<String, MutableList<String>>()

    init {
        load()
    }

    private fun load() {
        map.clear()
        val raw = prefs.getString("playlists", null) ?: return
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val arr = obj.getJSONArray(name)
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                map[name] = list
            }
        } catch (e: Exception) {
            // ignore corrupt data
        }
    }

    private fun save() {
        val obj = JSONObject()
        for ((name, list) in map) obj.put(name, JSONArray(list))
        prefs.edit().putString("playlists", obj.toString()).apply()
    }

    private fun uniqueName(wanted: String, ignore: String? = null): String {
        val base = wanted.trim().ifEmpty { "Playlist" }
        var result = base
        var i = 2
        while (map.containsKey(result) && result != ignore) {
            result = "$base ($i)"
            i++
        }
        return result
    }

    fun names(): List<String> = map.keys.toList()

    fun uris(name: String): List<String> = map[name]?.toList() ?: emptyList()

    fun create(name: String): String {
        val finalName = uniqueName(name)
        map[finalName] = ArrayList()
        save()
        return finalName
    }

    fun delete(name: String) {
        map.remove(name)
        save()
    }

    fun rename(old: String, newName: String): String {
        if (!map.containsKey(old)) return old
        if (newName.trim().isEmpty() || newName.trim() == old) return old
        val finalName = uniqueName(newName, old)
        val entries = map.entries.map { Pair(it.key, it.value) }
        map.clear()
        for ((k, v) in entries) map[if (k == old) finalName else k] = v
        save()
        return finalName
    }

    fun add(name: String, uri: String) {
        val list = map[name] ?: return
        if (!list.contains(uri)) {
            list.add(uri)
            save()
        }
    }

    fun remove(name: String, uri: String) {
        map[name]?.remove(uri)
        save()
    }

    fun setAll(name: String, uris: List<String>) {
        if (!map.containsKey(name)) return
        map[name] = ArrayList(uris)
        save()
    }
}
