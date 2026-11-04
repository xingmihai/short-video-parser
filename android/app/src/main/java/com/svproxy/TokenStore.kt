package com.svproxy

import android.content.Context

/** Token / Cookie 按平台 key 分别持久化，切换平台时自动回填 */
object TokenStore {
    private const val FILE = "sv_tokens"

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, platformKey: String): String =
        sp(context).getString(platformKey, "") ?: ""

    fun has(context: Context, platformKey: String): Boolean = get(context, platformKey).isNotEmpty()

    fun save(context: Context, platformKey: String, value: String) {
        sp(context).edit().apply {
            if (value.isEmpty()) remove(platformKey) else putString(platformKey, value)
            apply()
        }
    }

    fun clear(context: Context, platformKey: String) {
        sp(context).edit().remove(platformKey).apply()
    }
}
