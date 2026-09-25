package com.xlollx.songport.ytmbridge

import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** A hook for the host app: an interceptor every client of this module adds (the connections log). */
object BridgeNet {
    @Volatile var interceptor: Interceptor? = null

    fun OkHttpClient.Builder.withHook(): OkHttpClient.Builder = apply { interceptor?.let { addInterceptor(it) } }
}
