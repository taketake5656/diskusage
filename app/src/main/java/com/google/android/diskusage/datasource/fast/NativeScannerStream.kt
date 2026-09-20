package com.google.android.diskusage.datasource.fast

import com.google.android.diskusage.utils.AppHelper.appContext
import com.google.android.diskusage.utils.RootShell
import org.jetbrains.annotations.Contract
import java.io.IOException
import java.io.InputStream

class NativeScannerStream(private val process: Process) :
    InputStream() {
    private val input = process.inputStream

    @Throws(IOException::class)
    override fun read(): Int {
        return input.read()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray): Int {
        return input.read(buffer)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
        return input.read(buffer, byteOffset, byteCount)
    }

    @Throws(IOException::class)
    override fun close() {
        input.close()
        try {
            process.waitFor()
        } catch (e: InterruptedException) {
            throw IOException(e.message)
        }
    }

    companion object Factory {
        private const val LIBSCAN = "libscan.so"
        private val libscanPath = "${appContext.applicationInfo.nativeLibraryDir}/${LIBSCAN}"

        @JvmStatic
        @Contract("_, _ -> new")
        @Throws(IOException::class, InterruptedException::class)
        fun create(path: String, rootRequired: Boolean): NativeScannerStream {
            return runScanner(path, rootRequired)
        }

        @Contract("_, _ -> new")
        @Throws(IOException::class, InterruptedException::class)
        private fun runScanner(root: String, rootRequired: Boolean): NativeScannerStream {
            val process = if (!rootRequired) {
                Runtime.getRuntime().exec(arrayOf(libscanPath, root))
            } else {
                val su = RootShell.findSu()
                    ?: throw IOException("Root access is unavailable or was denied. Grant superuser access to DiskUsage.")
                val command = "${RootShell.shellQuote(libscanPath)} ${RootShell.shellQuote(root)}"
                Runtime.getRuntime().exec((su + listOf("-c", command)).toTypedArray())
            }
            return NativeScannerStream(process)
        }
    }
}
