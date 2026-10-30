package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The command line's way in (Cli.kt): at each start the running Trackhop makes a key and writes it,
 * with its own address on this machine, to cli.json in the data folder, readable by its user only.
 * The API accepts that key in place of its token, from the loopback address only: so `trackhop` on
 * the same computer, or inside the same container (`docker exec`), needs nothing set up, while
 * other machines still need the API's token. The file goes when Trackhop stops.
 */
object LocalKey {
    const val FILE = "cli.json"

    @Volatile private var key: String? = null

    /** A new key for this run, written with the loopback address of [port] into [dataDir]. */
    fun write(dataDir: File, port: Int) {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val made = "thl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val text = buildJsonObject { put("url", "http://127.0.0.1:$port"); put("key", made) }.toString()
        val target = File(dataDir, FILE)
        val tmp = File(dataDir, "$FILE.tmp")
        tmp.delete()
        // Owner-only from the first byte where the file system knows POSIX permissions.
        runCatching { Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
        tmp.writeText(text)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        key = made
        Runtime.getRuntime().addShutdownHook(Thread { if (key == made) runCatching { target.delete() } })
    }

    /** True for "Bearer <this run's key>" from the loopback address. */
    fun matches(header: String?, from: InetAddress?): Boolean {
        val current = key ?: return false
        if (from == null || !from.isLoopbackAddress) return false
        val given = header?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), current.toByteArray())
    }

    /** The address and key a running Trackhop left in [dataDir], or null when none is running there. */
    fun read(dataDir: File): Pair<String, String>? = runCatching {
        val o = Json.parseToJsonElement(File(dataDir, FILE).readText()).jsonObject
        val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val k = o["key"]?.jsonPrimitive?.contentOrNull ?: return null
        url to k
    }.getOrNull()

    /** For tests: forget this run's key. */
    internal fun reset() { key = null }
}
