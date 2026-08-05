package com.ferbotz.billanta.core

/**
 * Tiny persistent key-value storage for session/token/cursor state.
 * Backed by SharedPreferences on Android and NSUserDefaults on iOS.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String): Boolean?
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun clear()
}

/** For tests and previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, Any>()
    override fun getString(key: String): String? = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getLong(key: String): Long? = map[key] as? Long
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getBoolean(key: String): Boolean? = map[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() { map.clear() }
}
