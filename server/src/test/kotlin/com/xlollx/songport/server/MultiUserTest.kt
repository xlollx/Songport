package com.xlollx.songport.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** Several people on one server, through a real one on a free port: signing in, each one's own Trackhop, the admin's page, the API. */
class MultiUserTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var data: File
    private lateinit var hub: MultiUser
    private lateinit var base: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @Before fun start() {
        data = tmp.newFolder("data")
        val env = mapOf("SONGPORT_DATA" to data.path, "SONGPORT_PORT" to "0", "SONGPORT_PASSWORD" to "admin-secret", "SONGPORT_MULTIUSER" to "true")
        hub = MultiUser.serve(Config(env), env)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
    }

    @After fun stop() {
        hub.stop()
        LocalKey.reset()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun get(path: String, cookie: String? = null, vararg headers: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).apply { cookie?.let { header("Cookie", it) }; if (headers.isNotEmpty()) headers(*headers) }.GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, form: Map<String, String>, cookie: String? = null, origin: Boolean = true) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/x-www-form-urlencoded")
            .apply { cookie?.let { header("Cookie", it) }; if (origin) header("Origin", base) }
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** Signs in and returns the session cookie ("name=value"). */
    private fun signIn(user: String, password: String): String {
        val r = post("/login", mapOf("user" to user, "password" to password, "next" to "/"))
        assertEquals(r.body(), 303, r.statusCode())
        return r.headers().firstValue("Set-Cookie").get().substringBefore(';')
    }

    @Test fun signingInComesFirst() {
        val home = get("/")
        assertEquals(303, home.statusCode())
        assertEquals("/login", home.headers().firstValue("Location").get())
        assertEquals("/login?next=${enc("/tools")}", get("/tools").headers().firstValue("Location").get())
        assertEquals(200, get("/login").statusCode())
        assertEquals(200, get("/health").statusCode())
        assertEquals(200, get("/manifest.webmanifest").statusCode())
        assertEquals(401, post("/login", mapOf("user" to "admin", "password" to "wrong")).statusCode())
        assertEquals(401, post("/login", mapOf("user" to "nobody", "password" to "admin-secret")).statusCode())
        // A sign-in posted from another site is refused.
        assertEquals(403, post("/login", mapOf("user" to "admin", "password" to "admin-secret"), origin = false).let {
            client.send(HttpRequest.newBuilder(URI("$base/login")).header("Origin", "https://evil.example").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("user=admin&password=admin-secret")).build(), HttpResponse.BodyHandlers.ofString())
        }.statusCode())
        val admin = signIn("Admin", "admin-secret")
        val page = get("/", admin)
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("href=\"/users\""))
        // Only a path of this server after signing in.
        val away = post("/login", mapOf("user" to "admin", "password" to "admin-secret", "next" to "//evil.example/"))
        assertEquals("/", away.headers().firstValue("Location").get())
    }

    @Test fun eachPersonHasTheirOwnTrackhop() {
        val admin = signIn("admin", "admin-secret")
        assertTrue(post("/users", mapOf("action" to "add", "name" to "Anna", "password" to "short"), admin).body().contains("at least"))
        assertTrue(post("/users", mapOf("action" to "add", "name" to "../x", "password" to "long enough"), admin).body().contains("lowercase letters"))
        val added = post("/users", mapOf("action" to "add", "name" to "Anna", "password" to "anna-password"), admin)
        assertTrue(added.body(), added.body().contains("Added anna"))
        assertTrue(File(data, "users/anna").isDirectory)

        val anna = signIn("anna", "anna-password")
        val home = get("/", anna)
        assertEquals(200, home.statusCode())
        assertTrue(home.body().contains("anna"))
        assertFalse(home.body().contains("href=\"/users\""))
        // Her log is her engine's own: her data folder, not the admin's.
        val log = get("/log", anna).body()
        assertTrue(log, log.contains(File(data, "users/anna").path))
        assertFalse(get("/log", admin).body().contains(File(data, "users/anna").path))
        // The admin's page is the admin's only.
        assertEquals(404, get("/users", anna).statusCode())
        // Scripts may still send the user name and password with each request.
        val basic = "Basic " + Base64.getEncoder().encodeToString("anna:anna-password".toByteArray())
        assertEquals(200, get("/", null, "Authorization", basic).statusCode())

        // The command line's key: each person's Trackhop leaves its own in their folder, and the API answers as them.
        val annaKey = LocalKeyFile(File(data, "users/anna"))
        val adminKey = LocalKeyFile(data)
        assertNotNull(annaKey); assertNotNull(adminKey)
        assertTrue(annaKey != adminKey)
        assertEquals(200, get("/api/v1/status", null, "Authorization", "Bearer $annaKey").statusCode())
        assertEquals(200, get("/api/v1/status", null, "Authorization", "Bearer $adminKey").statusCode())
        // Nobody's key: the admin's Trackhop answers, its API being off.
        assertEquals(403, get("/api/v1/status", null, "Authorization", "Bearer nope").statusCode())

        // Her own password change signs her other devices out.
        val other = signIn("anna", "anna-password")
        val changed = post("/account", mapOf("current" to "anna-password", "new" to "anna-new-pass", "repeat" to "anna-new-pass"), anna)
        assertTrue(changed.body(), changed.body().contains("Password changed"))
        assertEquals(200, get("/", anna).statusCode())
        assertEquals(303, get("/", other).statusCode())

        // Removed: signed out, her Trackhop stopped, her data kept aside.
        assertTrue(post("/users", mapOf("action" to "remove", "name" to "anna"), admin).body().contains("Removed anna"))
        assertEquals(303, get("/", anna).statusCode())
        assertFalse(File(data, "users/anna").exists())
        assertTrue(File(data, "users-removed").listFiles()!!.any { it.name.startsWith("anna-") })
    }

    @Test fun peopleComeBackAfterARestart() {
        val admin = signIn("admin", "admin-secret")
        post("/users", mapOf("action" to "add", "name" to "marco", "password" to "marco-password"), admin)
        val marco = signIn("marco", "marco-password")
        hub.stop()
        LocalKey.reset()
        val env = mapOf("SONGPORT_DATA" to data.path, "SONGPORT_PORT" to "0", "SONGPORT_PASSWORD" to "admin-secret", "SONGPORT_MULTIUSER" to "true")
        hub = MultiUser.serve(Config(env), env)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
        // The session survives; so does the person.
        assertEquals(200, get("/", marco).statusCode())
        assertTrue(get("/users", admin).body().contains("marco"))
        // A new admin password in the container's settings signs the admin out, not the others.
        hub.stop()
        LocalKey.reset()
        val changed = env + ("SONGPORT_PASSWORD" to "new-admin-secret")
        hub = MultiUser.serve(Config(changed), changed)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
        assertEquals(303, get("/", admin).statusCode())
        assertEquals(200, get("/", marco).statusCode())
        signIn("admin", "new-admin-secret")
    }

    private fun LocalKeyFile(dir: File): String? = LocalKey.read(dir)?.second
}
