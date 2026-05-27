package com.rokid.relay.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object TestThreadStore {
    const val MAX_THREAD_INDEX = 999

    private const val MAX_HISTORY = 40
    private const val PREF_NEXT_THREAD_INDEX = "test_next_thread_index"
    private const val PREF_THREAD_PREFIX = "test_thread_messages_"

    fun nextThreadIndex(context: Context): Int =
        prefs(context)
            .getInt(PREF_NEXT_THREAD_INDEX, 1)
            .coerceIn(1, MAX_THREAD_INDEX)

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
        prefs(context)
            .edit()
            .remove(threadHistoryKey(normalizeThreadIndex(threadIndex)))
            .apply()
    }

    fun updateNextThreadIndex(context: Context, candidate: Int) {
        val store = prefs(context)
        val next = maxOf(store.getInt(PREF_NEXT_THREAD_INDEX, 1), candidate)
            .coerceIn(1, MAX_THREAD_INDEX)
        store.edit().putInt(PREF_NEXT_THREAD_INDEX, next).apply()
    }

    fun normalizeThreadIndex(threadIndex: Int): Int =
        threadIndex.coerceIn(1, MAX_THREAD_INDEX)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    private fun threadHistoryKey(threadIndex: Int): String =
        "$PREF_THREAD_PREFIX${normalizeThreadIndex(threadIndex)}"
}
