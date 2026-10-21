package com.xlollx.songport.server

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class YtmServerProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun env() = ServerEnv(tmp.root, Strings.load(null), "http://localhost:8080")

    @Test fun signedOutCookiesAreRefusedBeforeAnyCall() {
        val env = env()
        val p = YtmServerProvider()
        val e = runCatching { runBlocking { p.connect(env, "VISITOR_INFO1_LIVE=v; YSC=y", null) } }.exceptionOrNull()
        assertTrue(e is ProviderException)
        assertTrue(e!!.message!!.contains("SAPISID"))
        assertFalse(p.isConnected(env))
    }

    @Test fun storedCookiesConnectAndDisconnect() {
        val env = env()
        val p = YtmServerProvider()
        env.tokens.set(p.id, Tokens(accessToken = "SAPISID=a", userName = "Me"))
        assertTrue(p.isConnected(env))
        assertEquals("Me", p.accountName(env))
        p.disconnect(env)
        assertFalse(p.isConnected(env))
    }

    /** Not connected: the engine's own words, no request goes out. */
    @Test fun notConnectedSaysSo() {
        val env = env()
        val e = runCatching { runBlocking { YtmServerProvider().playlists(env) } }.exceptionOrNull()
        assertTrue(e is ProviderException)
    }
}
