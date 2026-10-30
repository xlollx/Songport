package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioDirectoryTest {

    @Test fun aServerUnderTwoNamesIsAskedOnce() {
        val dns = mapOf(
            "all.api.radio-browser.info" to listOf("91.98.4.78"),
            "de1.api.radio-browser.info" to listOf("91.98.4.78"),
            "de2.api.radio-browser.info" to listOf("91.98.4.78"),
            "at1.api.radio-browser.info" to listOf("5.6.7.8"),
        )
        assertEquals(listOf("all.api.radio-browser.info", "at1.api.radio-browser.info"), RadioDirectory.servers { dns[it] ?: throw java.net.UnknownHostException(it) })
    }

    @Test fun aNewServerBehindAllIsStillReached() {
        val dns = mapOf("all.api.radio-browser.info" to listOf("1.1.1.1", "2.2.2.2"), "de1.api.radio-browser.info" to listOf("2.2.2.2"))
        assertEquals(listOf("all.api.radio-browser.info"), RadioDirectory.servers { dns[it].orEmpty() })
    }

    @Test fun noServerFoundMeansNothingToAsk() {
        assertTrue(RadioDirectory.servers { throw java.net.UnknownHostException(it) }.isEmpty())
    }

    @Test fun theMessageNamesTheDirectoryOnce() {
        val m = RadioDirectory.Unavailable("HTTP 503").message!!
        assertEquals(1, Regex("radio-browser").findAll(m).count())
    }
}
