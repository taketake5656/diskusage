/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 * Copyright (C) 2022-2024 WhiredPlanck
 * Copyright (C) 2026 taketake5656
 *   2026: modified (see the Git history)
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

/**
 * @file NativeScannerStream.kt
 * @brief ネイティブスキャナ(libscan.so)の起動と、その出力の読み取り。
 */
package com.google.android.diskusage.datasource.fast

import com.google.android.diskusage.utils.AppHelper.appContext
import com.google.android.diskusage.utils.RootShell
import org.jetbrains.annotations.Contract
import java.io.IOException
import java.io.InputStream

/**
 * @brief root が必要なスキャンで、`su` が無いか利用者が root を拒否したときに投げる例外。
 */
class RootDeniedException : IOException("Root access is unavailable or was denied")

/**
 * @brief ネイティブスキャナのプロセスの標準出力を読むストリーム。
 *
 * 閉じるとプロセスの終了を待つ。
 *
 * @param process 起動したスキャナのプロセス
 */
class NativeScannerStream(private val process: Process) :
    InputStream() {
    private val input = process.inputStream

    /** @brief 1 バイト読む。 @return 読んだ値。終端なら -1 */
    @Throws(IOException::class)
    override fun read(): Int {
        return input.read()
    }

    /** @brief バッファいっぱいまで読む。 @return 読んだバイト数。終端なら -1 */
    @Throws(IOException::class)
    override fun read(buffer: ByteArray): Int {
        return input.read(buffer)
    }

    /**
     * @brief バッファの指定範囲に読む。
     * @param buffer 読み込み先
     * @param byteOffset 書き込み開始位置
     * @param byteCount 最大バイト数
     * @return 読んだバイト数。終端なら -1
     */
    @Throws(IOException::class)
    override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
        return input.read(buffer, byteOffset, byteCount)
    }

    /**
     * @brief ストリームを閉じ、スキャナのプロセスの終了を待つ。
     * @throws IOException 待機中に割り込まれたとき
     */
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

        /**
         * @brief スキャナを起動し、その出力を読むストリームを返す。
         * @param path スキャンするディレクトリ
         * @param rootRequired true なら `su` 経由で root 権限で起動する
         * @return スキャナの出力のストリーム
         * @throws RootDeniedException root が必要なのに使えないとき
         * @throws IOException 起動に失敗したとき
         */
        @JvmStatic
        @Contract("_, _ -> new")
        @Throws(IOException::class, InterruptedException::class)
        fun create(path: String, rootRequired: Boolean): NativeScannerStream {
            return runScanner(path, rootRequired)
        }

        /**
         * @brief libscan.so を直接、または `su -c` で起動する。
         * @param root スキャンするディレクトリ
         * @param rootRequired true なら root 権限で起動する
         * @return スキャナの出力のストリーム
         * @throws RootDeniedException root が必要なのに使えないとき
         */
        @Contract("_, _ -> new")
        @Throws(IOException::class, InterruptedException::class)
        private fun runScanner(root: String, rootRequired: Boolean): NativeScannerStream {
            val process = if (!rootRequired) {
                Runtime.getRuntime().exec(arrayOf(libscanPath, root))
            } else {
                val su = RootShell.findSu() ?: throw RootDeniedException()
                val command = "${RootShell.shellQuote(libscanPath)} ${RootShell.shellQuote(root)}"
                Runtime.getRuntime().exec((su + listOf("-c", command)).toTypedArray())
            }
            return NativeScannerStream(process)
        }
    }
}
