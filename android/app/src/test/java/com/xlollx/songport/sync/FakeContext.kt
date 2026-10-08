package com.xlollx.songport.sync

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * The little of Android the engine touches outside resources: a files directory for the store and
 * the diagnostics log. Resource strings go through the engine's own text function in tests.
 */
// Under Gradle's returnDefaultValues the SDK stubs answer null and zero: only what is overridden here is real.
class FakeContext : ContextWrapper(null) {
    private val dir: File = java.nio.file.Files.createTempDirectory("songport-test").toFile()
    override fun getFilesDir(): File = dir
    override fun getCacheDir(): File = dir
    override fun getApplicationContext(): Context = this
}
