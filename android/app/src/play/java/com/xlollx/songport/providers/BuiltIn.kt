package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Google Play build: no built-in web connectors. YouTube Music, Amazon Music and the web sign-ins
 * are offered only when a connector plugin (the Songport Bridge app) is installed.
 */
object BuiltIn {
    const val available = false

    @Suppress("UNUSED_PARAMETER")
    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle =
        throw IllegalStateException("no built-in connectors in this build")

    @Suppress("UNUSED_PARAMETER")
    fun loginIntent(ctx: Context, action: String): Intent? = null

    @Suppress("UNUSED_PARAMETER")
    fun extraActions(ctx: Context, serviceId: String): List<Pair<String, Intent>> = emptyList()
}
