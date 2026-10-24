package com.tekyerema.operator

/** In-memory [OperatorStore] for unit tests (moved out of the app in the 24 Oct 2026 cleanup: nothing in it used it). */
class MemoryOperatorStore : OperatorStore {
    val values = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = values[key]
    @Synchronized override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}
