package com.transdecoder

import android.content.Context

object Config {
    const val DEFAULT_SERVER_HOST = "skiff.algosculptor.com"
    const val PREF_KEY_SERVER_HOST = "custom_server_host"

    fun getCleanHost(rawHost: String): String {
        return rawHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .removePrefix("ws://")
            .trimEnd('/')
            .removeSuffix("/ws")
    }

    fun getServerHost(context: Context): String {
        val prefs = context.getSharedPreferences("skiff_prefs", Context.MODE_PRIVATE)
        val custom = prefs.getString(PREF_KEY_SERVER_HOST, null)
        return if (!custom.isNullOrBlank()) getCleanHost(custom) else DEFAULT_SERVER_HOST
    }

    fun setServerHost(context: Context, rawHost: String?) {
        val prefs = context.getSharedPreferences("skiff_prefs", Context.MODE_PRIVATE)
        if (rawHost.isNullOrBlank() || getCleanHost(rawHost) == DEFAULT_SERVER_HOST) {
            prefs.edit().remove(PREF_KEY_SERVER_HOST).apply()
        } else {
            prefs.edit().putString(PREF_KEY_SERVER_HOST, getCleanHost(rawHost)).apply()
        }
    }

    fun getSignalingUrl(context: Context): String {
        val host = getServerHost(context)
        val scheme = if (host.startsWith("localhost") || host.startsWith("127.0.0.1") || host.startsWith("10.") || host.startsWith("192.168.")) "ws" else "wss"
        return "$scheme://$host/ws"
    }

    fun getApiUrl(context: Context): String {
        val host = getServerHost(context)
        val scheme = if (host.startsWith("localhost") || host.startsWith("127.0.0.1") || host.startsWith("10.") || host.startsWith("192.168.")) "http" else "https"
        return "$scheme://$host"
    }
}
