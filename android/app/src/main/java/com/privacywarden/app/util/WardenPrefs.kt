package com.privacywarden.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny wrapper around SharedPreferences for user choices that need to survive
 * across launches: which Gmail account to watch, which phone number's SMS to
 * focus on, etc.
 */
object WardenPrefs {
    private const val FILE = "warden_prefs"
    private const val K_EMAIL = "selected_email"
    private const val K_PHONE = "selected_phone"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selectedEmail(ctx: Context): String? = p(ctx).getString(K_EMAIL, null)
    fun setSelectedEmail(ctx: Context, email: String?) {
        p(ctx).edit().also { if (email == null) it.remove(K_EMAIL) else it.putString(K_EMAIL, email) }.apply()
    }

    /** "ALL" or a specific phone number / sender id. */
    fun selectedPhone(ctx: Context): String? = p(ctx).getString(K_PHONE, null)
    fun setSelectedPhone(ctx: Context, phone: String?) {
        p(ctx).edit().also { if (phone == null) it.remove(K_PHONE) else it.putString(K_PHONE, phone) }.apply()
    }
}
