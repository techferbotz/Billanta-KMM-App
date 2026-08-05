package com.ferbotz.billanta.core

import android.content.Context
import android.content.SharedPreferences

class SharedPrefsKeyValueStore(context: Context, name: String = "billanta_store") : KeyValueStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun getBoolean(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun clear() = prefs.edit().clear().apply()
}
