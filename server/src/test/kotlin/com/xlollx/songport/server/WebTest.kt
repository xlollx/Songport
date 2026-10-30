package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** The web page's own checks, through a real server on a free port: password, cross-site posts, methods, ids. */
class WebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        val env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun get(path: String, withAuth: Boolean = true) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).apply { if (withAuth) header("Authorization", auth) }.GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String, vararg headers: String) =
        client.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Content-Type", "application/x-www-form-urlencoded")
                .apply { if (headers.isNotEmpty()) headers(*headers) }.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private val origin get() = arrayOf("Origin", base)

    @Test fun everyPageAsksForThePassword() {
        assertEquals(401, get("/", withAuth = false).statusCode())
        assertEquals(401, get("/log", withAuth = false).statusCode())
        assertEquals(200, get("/health", withAuth = false).statusCode())
        assertEquals(200, get("/").statusCode())
    }

    @Test fun ownFormsWorkInBrowsers() {
        // "no-referrer" makes browsers send "Origin: null" on the page's own forms, which is refused.
        assertEquals("same-origin", get("/").headers().firstValue("Referrer-Policy").orElse(""))
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
    }

    @Test fun crossSitePostsAreRefused() {
        assertEquals(403, post("/connectors/add", "service=csv", "Origin", "http://evil.example").statusCode())
        assertEquals(403, post("/connectors/add", "service=csv", "Origin", "null").statusCode())
        assertEquals(403, post("/connectors/add", "service=csv", "Sec-Fetch-Site", "cross-site").statusCode())
        assertEquals(403, post("/connectors/add", "service=csv", "Referer", "http://evil.example/page").statusCode())
        assertFalse(get("/connectors").body().contains("href=\"/connectors/csv\""))
        // Behind a reverse proxy that rewrites Host: the address in SONGPORT_URL.
        assertEquals(303, post("/connectors/add", "service=csv", "Origin", "https://songport.example.org").statusCode())
    }

    @Test fun sameOriginRules() {
        fun check(vararg h: Pair<String, String>) = sameOrigin({ name -> h.firstOrNull { it.first.equals(name, true) }?.second }, "https://songport.example.org")
        assertTrue(check("Host" to "nas:8080", "Origin" to "http://nas:8080"))
        assertTrue(check("Host" to "nas:8080", "Origin" to "https://songport.example.org"))
        assertFalse(check("Host" to "nas:8080", "Origin" to "http://nas:8080.evil.example"))
        assertFalse(check("Host" to "nas:8080", "Origin" to "null"))
        assertTrue(check("Host" to "nas:8080", "Sec-Fetch-Site" to "same-origin"))
        assertFalse(check("Host" to "nas:8080", "Sec-Fetch-Site" to "same-site"))
        assertTrue(check("Host" to "nas:8080", "Referer" to "http://nas:8080/syncs/x"))
        assertFalse(check("Host" to "nas:8080", "Referer" to "http://evil.example/nas:8080"))
        // No browser header at all (curl, a script): not a cross-site form; the password still applies.
        assertTrue(check("Host" to "nas:8080"))
    }

    @Test fun actionsNeedAPost() {
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        for (action in listOf("remove", "disconnect", "connect", "settings")) {
            assertEquals(action, 405, get("/connectors/csv/$action").statusCode())
        }
        assertTrue(get("/connectors").body().contains("href=\"/connectors/csv\""))
        assertEquals(303, post("/connectors/csv/remove", "", *origin).statusCode())
        assertFalse(get("/connectors").body().contains("href=\"/connectors/csv\""))
    }

    @Test fun aSyncOnlyTakesListedPlaylists() {
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        File(data, "csv").mkdirs()
        File(data, "csv/mine.csv").writeText("title,artist\nSong,Someone\n")
        // A playlist id that walks out of the CSV folder, on either side.
        val out = post("/syncs", "src=csv&dst=csv&srcPlaylist=mine&dstPlaylist=..%2F..%2Fescape&schedule=MANUAL", *origin)
        assertTrue(out.headers().firstValue("Location").orElse(""), out.headers().firstValue("Location").orElse("").contains("err="))
        val inside = post("/syncs", "src=csv&dst=csv&srcPlaylist=..%2Fsecret&schedule=MANUAL", *origin)
        assertTrue(inside.headers().firstValue("Location").orElse("").contains("err="))
        assertTrue(get("/").body().contains("No syncs yet."))
        // The listed one, to a new playlist: created.
        val ok = post("/syncs", "src=csv&dst=csv&srcPlaylist=mine&dstPlaylist=&name=Mine&schedule=MANUAL", *origin)
        assertTrue(ok.headers().firstValue("Location").orElse(""), ok.headers().firstValue("Location").orElse("").startsWith("/syncs/"))
        assertTrue(get("/").body().contains("Mine"))
    }

    @Test fun playlistFilesDownloadAndNothingElse() {
        File(data, "csv").mkdirs()
        File(data, "csv/Road trip.csv").writeText("title,artist\nSong,Band\n")
        File(data, "secret.csv").writeText("no")
        val ok = get("/files/Road%20trip.csv")
        assertEquals(200, ok.statusCode())
        assertTrue(ok.body().contains("Song,Band"))
        assertTrue(ok.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"))
        assertTrue(get("/connectors/csv").body().contains("/files/Road+trip.csv"))
        assertEquals(404, get("/files/..%2Fsecret.csv").statusCode())
        assertEquals(404, get("/files/tokens.json").statusCode())
        assertEquals(401, get("/files/Road%20trip.csv", withAuth = false).statusCode())
    }
}
