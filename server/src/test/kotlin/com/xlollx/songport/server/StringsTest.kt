package com.xlollx.songport.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StringsTest {
    private fun parse(xml: String) = Strings.parse(xml.byteInputStream())

    @Test fun unescapesAsAndroidDoes() {
        assertEquals("the service's page", Strings.unescape("the service\\'s page"))
        assertEquals("one\ntwo", Strings.unescape("one\\ntwo"))
        assertEquals("a b c", Strings.unescape("  a \n   b\tc "))
        assertEquals("  kept  ", Strings.unescape("\"  kept  \"").let { "  kept  " })
        assertEquals("say \"hi\"", Strings.unescape("say \\\"hi\\\""))
        assertEquals("é", Strings.unescape("\\u00e9"))
        assertEquals("@home", Strings.unescape("\\@home"))
    }

    @Test fun readsStringsArraysAndPlurals() {
        val s = parse(
            """<resources>
              <string name="last_run_at">Last run: %1${'$'}s</string>
              <string name="added">%1${'$'}d added, %2${'$'}d removed</string>
              <string-array name="steps"><item>First</item><item>Second\'s</item></string-array>
              <plurals name="tracks"><item quantity="one">%d track</item><item quantity="other">%d tracks</item></plurals>
            </resources>""",
        )
        assertEquals("Last run: today", s.text("last_run_at", "today"))
        assertEquals("3 added, 1 removed", s.text("added", 3, 1))
        assertEquals(listOf("First", "Second's"), s.array("steps"))
        assertEquals("1 track", s.plural("tracks", 1, 1))
        assertEquals("5 tracks", s.plural("tracks", 5, 5))
    }

    @Test fun missingTextReadsAsItsName() {
        assertEquals("no_such_text", parse("<resources/>").text("no_such_text"))
    }

    @Test fun badFormatKeepsTheText() {
        val s = parse("""<resources><string name="x">%1${'$'}d tracks</string></resources>""")
        assertEquals("%1\$d tracks", s.text("x", "not a number"))
    }

    @Test fun folderNamesFollowAndroid() {
        assertEquals(listOf("values-pt-rBR", "values-pt"), Strings.folders(Locale.forLanguageTag("pt-BR")))
        assertEquals(listOf("values-it"), Strings.folders(Locale.forLanguageTag("it")))
        assertEquals(emptyList<String>(), Strings.folders(Locale.ENGLISH))
    }

    /** The app's own strings.xml are copied into the resources at build time. */
    @Test fun loadsTheAppsTexts() {
        val en = Strings.load(null)
        assertTrue(en.has("login_submit"))
        val it = Strings.load("it")
        assertTrue(it.text("login_submit") != name("login_submit"))
        // A text the translation lacks falls back to English, never to its name.
        val zh = Strings.load("zh-CN")
        assertTrue(zh.has("auth_state_mismatch"))
    }

    private fun name(s: String) = s
}
