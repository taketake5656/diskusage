package com.google.android.diskusage.utils

import timber.log.Timber
import java.io.File

object DeviceHelper {
    private val SU = "su"
    private val suLocations = arrayOf(
        "/system/bin/", "/system/xbin/", "/sbin/", "/system/sd/xbin/",
        "/system/bin/failsafe/", "/data/local/xbin/", "/data/local/bin/", "/data/local/",
        "/system/sbin/", "/usr/bin/", "/vendor/bin/", "/product/bin/", "/debug_ramdisk/"
    )

    /**
     * Cheap heuristic used to decide whether to offer the root entries in the UI.
     * It never launches `su`, so it is safe to call on the main thread.
     */
    @JvmStatic
    fun isDeviceRooted(): Boolean {
        val pathDirs = System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }
        return (suLocations.asSequence() + pathDirs.asSequence())
            .any { location -> File(location, SU).exists() }
    }
}

/**
 * Locates a working `su` (Magisk, KernelSU, APatch, ...) by actually running it.
 * Blocking: may show the superuser grant prompt, so call it from a worker thread.
 */
object RootShell {
    // Prefer the master mount namespace so that the real /data/media, Android/data
    // and friends are visible even when the root manager isolates namespaces.
    private val candidates = listOf(
        listOf("su", "--mount-master"),
        listOf("su"),
        listOf("/system/bin/su"),
        listOf("/system/xbin/su"),
    )

    @Volatile
    private var working: List<String>? = null

    /** Returns the command prefix (to be followed by `-c <cmd>`) or null if root is unavailable. */
    @Synchronized
    @JvmStatic
    fun findSu(): List<String>? {
        working?.let { return it }
        for (candidate in candidates) {
            if (probe(candidate)) {
                Timber.d("RootShell: using %s", candidate)
                working = candidate
                return candidate
            }
        }
        return null
    }

    private fun probe(candidate: List<String>): Boolean = runCatching {
        val process = Runtime.getRuntime().exec((candidate + listOf("-c", "id")).toTypedArray())
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.contains("uid=0")
    }.getOrDefault(false)

    @JvmStatic
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
