package com.playtorrio.tv.data.mylist

import com.playtorrio.tv.data.AppPreferences
import org.json.JSONArray

/** Local "My List" bookmarks — synced via PlayTorrio Cloud `my_list_items`. */
object MyListStore {
    fun load(): List<MyListItem> {
        return try {
            val raw = AppPreferences.myListItems
            if (raw.isBlank() || raw == "[]") return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            MyListItem.fromJson(arr.getJSONObject(i))
        }.sortedByDescending { it.addedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun contains(uniqueId: String): Boolean = load().any { it.uniqueId == uniqueId }

    fun add(item: MyListItem): List<MyListItem> {
        if (contains(item.uniqueId)) return load()
        val merged = listOf(item) + load().filterNot { it.uniqueId == item.uniqueId }
        persist(merged)
        return merged
    }

    fun remove(uniqueId: String): List<MyListItem> {
        val merged = load().filterNot { it.uniqueId == uniqueId }
        persist(merged)
        return merged
    }

    fun toggle(item: MyListItem): Boolean {
        return if (contains(item.uniqueId)) {
            remove(item.uniqueId)
            false
        } else {
            add(item)
            true
        }
    }

    fun replaceAll(items: List<MyListItem>) {
        persist(items.sortedByDescending { it.addedAt })
    }

    private fun persist(items: List<MyListItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        AppPreferences.myListItems = arr.toString()
    }
}
