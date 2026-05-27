package com.rokid.relay.phone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal object TestThreadStore {
    const val MAX_THREAD_INDEX = 999

    private const val MAX_HISTORY = 40
    private const val PREF_NEXT_THREAD_INDEX = "test_next_thread_index"
    private const val PREF_THREAD_PREFIX = "test_thread_messages_"

    fun nextThreadIndex(context: Context): Int =
        syncNextThreadIndex(prefs(context))

    fun load(context: Context, threadIndex: Int): List<TestThreadMessage> {
        val raw = prefs(context).getString(threadHistoryKey(threadIndex), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TestThreadMessage(
                            text = item.optString("text"),
                            sender = item.optString("sender", "Mika"),
                            timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                            outgoing = item.optBoolean("outgoing", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, threadIndex: Int, messages: List<TestThreadMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_HISTORY).forEach { message ->
            array.put(
                JSONObject()
                    .put("text", message.text)
                    .put("sender", message.sender)
                    .put("timestamp", message.timestamp)
                    .put("outgoing", message.outgoing),
            )
        }
        prefs(context)
            .edit()
            .putString(threadHistoryKey(threadIndex), array.toString())
            .apply()
    }

    fun appendUserReply(context: Context, threadIndex: Int, reply: String) {
        val normalizedThread = normalizeThreadIndex(threadIndex)
        val messages = load(context, normalizedThread) + TestThreadMessage(
            text = reply,
            sender = "You",
            timestamp = System.currentTimeMillis(),
            outgoing = true,
        )
        save(context, normalizedThread, messages)
    }

    fun clear(context: Context, threadIndex: Int) {
        val store = prefs(context)
        store
            .edit()
            .remove(threadHistoryKey(normalizeThreadIndex(threadIndex)))
            .apply()
        syncNextThreadIndex(store)
    }

    fun clearAll(context: Context): List<Int> {
        val store = prefs(context)
        val clearedThreads = savedThreadIndices(store)
        val editor = store.edit()
        store.all.keys
            .filter { key -> key == PREF_NEXT_THREAD_INDEX || key.startsWith(PREF_THREAD_PREFIX) }
            .forEach { key -> editor.remove(key) }
        editor.putInt(PREF_NEXT_THREAD_INDEX, 1).apply()
        return clearedThreads
    }

    fun updateNextThreadIndex(context: Context, candidate: Int) {
        syncNextThreadIndex(prefs(context), candidate)
    }

    fun normalizeThreadIndex(threadIndex: Int): Int =
        threadIndex.coerceIn(1, MAX_THREAD_INDEX)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    private fun syncNextThreadIndex(store: SharedPreferences, preferredStart: Int = 1): Int {
        val occupiedThreads = savedThreadIndices(store).toSet()
        val start = preferredStart.coerceIn(1, MAX_THREAD_INDEX)
        val next = firstFreeThreadIndex(occupiedThreads, start)
            ?: firstFreeThreadIndex(occupiedThreads, 1)
            ?: MAX_THREAD_INDEX
        store.edit().putInt(PREF_NEXT_THREAD_INDEX, next).apply()
        return next
    }

    private fun savedThreadIndices(store: SharedPreferences): List<Int> =
        store.all.keys
            .asSequence()
            .filter { key -> key.startsWith(PREF_THREAD_PREFIX) }
            .mapNotNull { key -> key.removePrefix(PREF_THREAD_PREFIX).toIntOrNull() }
            .map { index -> normalizeThreadIndex(index) }
            .distinct()
            .sorted()
            .toList()

    private fun firstFreeThreadIndex(occupiedThreads: Set<Int>, start: Int): Int? =
        (start..MAX_THREAD_INDEX).firstOrNull { index -> index !in occupiedThreads }

    private fun threadHistoryKey(threadIndex: Int): String =
        "$PREF_THREAD_PREFIX${normalizeThreadIndex(threadIndex)}"
}
