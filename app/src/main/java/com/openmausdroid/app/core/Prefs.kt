package com.openmausdroid.app.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("maus", Context.MODE_PRIVATE)
    }

    var setupVersion: Int
        get() = prefs.getInt("setup_version", 0)
        set(value) = prefs.edit().putInt("setup_version", value).apply()

    var activeProotPath: String
        get() = prefs.getString("active_proot", "").orEmpty()
        set(value) = prefs.edit().putString("active_proot", value).apply()

    var promptEnforced: Boolean
        get() = prefs.getBoolean("prompt_enforced", false)
        set(value) = prefs.edit().putBoolean("prompt_enforced", value).apply()

    var notifAsked: Boolean
        get() = prefs.getBoolean("notif_asked", false)
        set(value) = prefs.edit().putBoolean("notif_asked", value).apply()

    var storageAsked: Boolean
        get() = prefs.getBoolean("storage_asked", false)
        set(value) = prefs.edit().putBoolean("storage_asked", value).apply()

    var batteryAsked: Boolean
        get() = prefs.getBoolean("battery_asked", false)
        set(value) = prefs.edit().putBoolean("battery_asked", value).apply()
}
