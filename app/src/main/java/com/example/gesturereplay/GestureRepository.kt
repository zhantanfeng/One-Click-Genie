package com.example.gesturereplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class GestureRepository(context: Context) {
    private val preferences = context.getSharedPreferences("gesture_records", Context.MODE_PRIVATE)

    fun load(): List<GestureRecord> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RECORDS, "[]"))
        List(array.length()) { index -> array.getJSONObject(index).toGestureRecord() }
    }.getOrDefault(emptyList())

    fun save(records: List<GestureRecord>) {
        val array = JSONArray().apply { records.forEach { put(it.toJson()) } }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    fun loadDraft(): RecordingDraft? = runCatching {
        preferences.getString(KEY_DRAFT, null)?.let { JSONObject(it).toRecordingDraft() }
    }.getOrNull()

    fun saveDraft(draft: RecordingDraft) {
        preferences.edit().putString(KEY_DRAFT, draft.toJson().toString()).apply()
    }

    fun clearDraft() {
        preferences.edit().remove(KEY_DRAFT).apply()
    }

    private companion object {
        const val KEY_RECORDS = "records"
        const val KEY_DRAFT = "draft"
    }
}
