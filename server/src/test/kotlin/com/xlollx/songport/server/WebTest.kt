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
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
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
        // A sync that writes to File offers its CSV on its card.
        env.store.upsertJob(com.xlollx.songport.model.SyncJob("j1", "Trip", com.xlollx.songport.model.PlaylistRef("subsonic", "p", "Trip"), com.xlollx.songport.model.PlaylistRef("csv", "Road trip", "Road trip")))
        assertTrue(get("/").body().contains("Download CSV"))
    }

    @Test fun reviewPicksIgnoresAndConfirms() {
        File(data, "csv").mkdirs()
        File(data, "csv/Out.csv").writeText("title,artist\n")
        val job = com.xlollx.songport.model.SyncJob("j2", "Mix", com.xlollx.songport.model.PlaylistRef("csv", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out"))
        env.store.upsertJob(job)
        val a = com.xlollx.songport.model.Track(id = "a", title = "Alpha", artists = listOf("One"))
        val b = com.xlollx.songport.model.Track(id = "b", title = "Beta", artists = listOf("Two"))
        val c = com.xlollx.songport.model.Track(id = "c", title = "Gamma", artists = listOf("Three"))
        env.store.addReport(
            com.xlollx.songport.model.SyncReport(
                id = "r1", jobId = "j2", jobName = "Mix", startedEpoch = 1, durationMs = 1,
                unmatched = listOf(a.toString(), b.toString()), unmatchedTracks = listOf(a, b),
                reviewTracks = listOf(com.xlollx.songport.model.MatchReview(c, c.copy(id = "c2"), 0.7)),
            ),
        )
        val page = get("/syncs/j2/review/r1")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Alpha") && page.body().contains("Gamma"))
        assertTrue(get("/syncs/j2").body().contains("/syncs/j2/review/r1"))
        // Pick a track for Alpha: it goes into the destination and leaves the list.
        val chosen = java.net.URLEncoder.encode("""{"id":"x1","title":"Alpha","artists":["One"]}""", "UTF-8")
        assertEquals(303, post("/syncs/j2/review/r1/resolve", "track=a&chosen=$chosen", *origin).statusCode())
        assertTrue(File(data, "csv/Out.csv").readText().contains("Alpha"))
        // Ignore Beta; keep Gamma's match.
        assertEquals(303, post("/syncs/j2/review/r1/ignore", "track=b", *origin).statusCode())
        assertEquals(303, post("/syncs/j2/review/r1/confirm", "track=c", *origin).statusCode())
        val rep = env.store.data.reports.first { it.id == "r1" }
        assertTrue(rep.unmatchedTracks.isEmpty() && rep.reviewTracks.isEmpty())
        assertTrue("b" in env.store.job("j2")!!.ignoredSourceIds)
        assertTrue(get("/syncs/j2/review/r1").body().contains("Nothing left to review"))
        // Another sync's run is not reachable through this one.
        assertEquals(404, get("/syncs/j1/review/r1").statusCode())
    }

    @Test fun playlistFilesUploadInAnyFormatAndExportInOthers() {
        val boundary = "----trackhop"
        val m3u = "#EXTM3U\r\n#EXTINF:200,Band - Song\r\nBand - Song.mp3\r\n"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"Città.m3u\"\r\nContent-Type: audio/x-mpegurl\r\n\r\n$m3u\r\n--$boundary--\r\n"
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/files")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(303, res.statusCode())
        assertTrue(res.headers().firstValue("Location").orElse("").contains("ok="))
        val csv = File(data, "csv/Città.csv")
        assertTrue(csv.exists() && csv.readText().contains("Song"))
        val xspf = get("/files/Citt%C3%A0.csv?format=xspf")
        assertEquals(200, xspf.statusCode())
        assertTrue(xspf.body().contains("<creator>Band</creator>"))
        assertTrue(get("/files/Citt%C3%A0.csv?format=txt").body().trim() == "Band - Song")
        assertEquals(303, post("/files/Citt%C3%A0.csv", "action=delete", *origin).statusCode())
        assertFalse(csv.exists())
        assertEquals(200, get("/tools").statusCode())
        assertEquals(200, get("/tools/export").statusCode())
    }

    @Test fun toolsFindAndRemoveDuplicatesInTheBackground() {
        File(data, "csv").mkdirs()
        File(data, "csv/Dup.csv").writeText("title,artist\nSong,Band\nOther,Band\nSong,Band\n")
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        val found = get("/tools/dedupe?service=csv&playlist=Dup")
        assertEquals(200, found.statusCode())
        assertTrue(found.body(), found.body().contains("more than once"))
        val started = post("/tools/dedupe", "service=csv&playlist=Dup", *origin)
        assertEquals(303, started.statusCode())
        val task = started.headers().firstValue("Location").get()
        assertTrue(task.startsWith("/tools/task/"))
        var page = ""
        repeat(50) { if (!page.contains("Removed")) { Thread.sleep(100); page = get(task).body() } }
        assertTrue(page, page.contains("Removed 1 extra copies"))
        assertEquals(2, File(data, "csv/Dup.csv").readLines().size - 1)
        // Backup settings: a folder that is not there is refused, a real one kept.
        assertTrue(post("/tools/backup", "action=settings&schedule=DAILY&folder=/no/such/dir", *origin).headers().firstValue("Location").get().contains("err="))
        val folder = tmp.newFolder("copies")
        assertEquals(303, post("/tools/backup", "action=settings&schedule=WEEKLY&folder=${java.net.URLEncoder.encode(folder.path, "UTF-8")}", *origin).statusCode())
        assertEquals(com.xlollx.songport.model.Schedule.WEEKLY, env.store.data.settings.backupSchedule)
        assertEquals(1, Backups.copyToFolder(env))
        assertTrue(File(folder, "Dup.csv").exists())
        assertEquals(200, get("/tools/unplayable").statusCode())
        assertEquals(200, get("/tools").statusCode())
    }
}
