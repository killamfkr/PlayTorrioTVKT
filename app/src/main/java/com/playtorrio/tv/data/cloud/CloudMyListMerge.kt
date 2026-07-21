package com.playtorrio.tv.data.cloud

import com.playtorrio.tv.data.mylist.MyListItem
import org.json.JSONArray
import org.json.JSONObject

/** V2 merge for `my_list_items` — union by uniqueId, newer addedAt wins. */
object CloudMyListMerge {
    fun merge(local: List<MyListItem>, remote: List<MyListItem>): List<MyListItem> {
        val byId = LinkedHashMap<String, MyListItem>()
        for (item in remote) byId[item.uniqueId] = item
        for (item in local) {
            val existing = byId[item.uniqueId]
            byId[item.uniqueId] = when {
                existing == null -> item
                item.addedAt >= existing.addedAt -> item
                else -> existing
            }
        }
        return byId.values.sortedByDescending { it.addedAt }
    }

    fun parseJsonString(raw: String?): List<MyListItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                MyListItem.fromJson(arr.getJSONObject(i))
            }
        }.getOrDefault(emptyList())
    }

    fun toJsonString(items: List<MyListItem>): String {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun parseFromPrefsValue(raw: Any?): List<MyListItem> = when (raw) {
        is String -> parseJsonString(raw)
        is JSONArray -> (0 until raw.length()).mapNotNull { i ->
            MyListItem.fromJson(raw.getJSONObject(i))
        }
        else -> emptyList()
    }
}
