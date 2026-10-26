package com.xlollx.songport.server

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The diagnostic log: each line goes to standard output (what `docker logs` shows), to the last
 * lines kept for the web page, and to `songport.log` in the data folder, rotated at 2 MB.
 */
object Log {
    private const val KEEP = 1000
    private const val MAX_FILE = 2L * 1024 * 1024
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val lines = ArrayDeque<String>()
    @Volatile private var file: File? = null

    fun init(dataDir: File) { file = File(dataDir, "songport.log") }

    fun add(tag: String, message: String) {
        val line = "${LocalDateTime.now().format(time)} [$tag] $message"
        println(line)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > KEEP) lines.removeFirst()
            file?.let { f ->
                runCatching {
                    if (f.length() > MAX_FILE) f.renameTo(File(f.parentFile, f.name + ".1"))
                    f.appendText(line + "\n")
                }
            }
        }
    }

    /** The latest lines, oldest first. */
    fun recent(): List<String> = synchronized(lines) { lines.toList() }
}
