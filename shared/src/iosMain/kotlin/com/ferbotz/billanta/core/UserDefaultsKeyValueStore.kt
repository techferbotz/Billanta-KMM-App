package com.ferbotz.billanta.core

import platform.Foundation.NSUserDefaults

class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val prefix: String = "billanta.",
) : KeyValueStore {

    private fun k(key: String) = prefix + key

    override fun getString(key: String): String? = defaults.stringForKey(k(key))
    override fun putString(key: String, value: String) = defaults.setObject(value, k(key))
    override fun getLong(key: String): Long? =
        if (defaults.objectForKey(k(key)) != null) defaults.integerForKey(k(key)) else null
    override fun putLong(key: String, value: Long) = defaults.setInteger(value, k(key))
    override fun getBoolean(key: String): Boolean? =
        if (defaults.objectForKey(k(key)) != null) defaults.boolForKey(k(key)) else null
    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, k(key))
    override fun remove(key: String) = defaults.removeObjectForKey(k(key))

    override fun clear() {
        val keys = defaults.dictionaryRepresentation().keys
        keys.filterIsInstance<String>().filter { it.startsWith(prefix) }
            .forEach { defaults.removeObjectForKey(it) }
    }
}
