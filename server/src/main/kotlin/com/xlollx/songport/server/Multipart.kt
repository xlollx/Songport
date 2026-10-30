package com.xlollx.songport.server

import java.nio.charset.StandardCharsets

/** The small part of multipart/form-data the pages use: text fields and uploaded files. */
object Multipart {
    class File(val name: String, val bytes: ByteArray)
    class Parts(val fields: Map<String, String>, val files: Map<String, File>)

    fun parse(contentType: String, body: ByteArray): Parts {
        val boundary = contentType.substringAfter("boundary=", "").trim().trim('"').ifEmpty { return Parts(emptyMap(), emptyMap()) }
        // ISO-8859-1 maps every byte to one char and back: splitting the text splits the bytes.
        val text = String(body, StandardCharsets.ISO_8859_1)
        val fields = HashMap<String, String>()
        val files = HashMap<String, File>()
        for (raw in text.split("--$boundary")) {
            val part = raw.removePrefix("\r\n")
            if (part.isBlank() || part.startsWith("--")) continue
            val headerEnd = part.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: continue
            val headers = part.substring(0, headerEnd)
            val content = part.substring(headerEnd + 4).removeSuffix("\r\n")
            val disposition = headers.lineSequence().firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) } ?: continue
            val name = attr(disposition, "name") ?: continue
            val fileName = attr(disposition, "filename")
            val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
            if (fileName != null) {
                if (bytes.isNotEmpty()) files[name] = File(String(fileName.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8), bytes)
            } else fields[name] = String(bytes, StandardCharsets.UTF_8)
        }
        return Parts(fields, files)
    }

    private fun attr(header: String, key: String): String? =
        Regex("""(?:^|;)\s*$key="([^"]*)"""").find(header)?.groupValues?.get(1)
}
