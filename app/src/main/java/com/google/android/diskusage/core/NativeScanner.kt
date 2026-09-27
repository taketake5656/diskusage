/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 * Copyright (C) 2026 taketake5656
 *   2026: converted to Kotlin and modified (see the Git history)
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
 * @file NativeScanner.kt
 * @brief ネイティブスキャナ(libscan.so)の出力からツリーを作る。
 */
package com.google.android.diskusage.core

import com.google.android.diskusage.datasource.fast.NativeScannerStream
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import java.io.InputStream

/**
 * @brief ネイティブスキャナの出力からファイルシステムのツリーを作る。
 *
 * 出力は 0 バイトで始まり、0 で終わるフィールドが続く。
 * - `D<名前>\0<ブロック数>\0<バイト数>\0` はディレクトリの始まり。子が続き、`Z` で終わる。
 * - `F<名前>\0<ブロック数>\0<バイト数>\0` はファイル。
 *
 * ブロック数は 512 バイト単位。
 *
 * @param blockSize 表示のブロックサイズ(バイト)
 * @param allocatedBlocks ストレージの使用済みブロック数(まとめる項目のしきい値の計算に使う)
 * @param maxHeap ツリーに使ってよいヒープの量(バイト)
 * @param smallEntryName 小さなファイルをまとめた項目の名前
 */
class NativeScanner(
    blockSize: Long,
    allocatedBlocks: Long,
    maxHeap: Int,
    smallEntryName: SmallEntryName = SmallEntryName.DEFAULT,
) : TreeScanner(blockSize, allocatedBlocks, maxHeap, smallEntryName) {

    private lateinit var input: InputStream
    private val buffer = ByteArray(BUFFER_SIZE)
    private var offset = 0
    private var allocated = 0

    /**
     * @brief 読み終えた部分を捨て、未処理のデータをバッファの先頭に詰める。
     * @throws RuntimeException 1 つのフィールドがバッファより大きいとき
     */
    private fun move() {
        if (offset == 0) throw RuntimeException("Error: too large entity size")
        buffer.copyInto(buffer, 0, offset, allocated)
        allocated -= offset
        offset = 0
    }

    /**
     * @brief ストリームからバッファに追加で読み込む。
     * @throws RuntimeException データが途中で終わったとき
     */
    private fun read() {
        if (allocated == BUFFER_SIZE) {
            move()
        }
        val res = input.read(buffer, allocated, BUFFER_SIZE - allocated)
        if (res <= 0) {
            throw RuntimeException("Error: no more data")
        }
        allocated += res
    }

    /** @brief 1 バイト読む。 @return 読んだバイト */
    private fun getByte(): Byte {
        while (offset >= allocated) {
            read()
        }
        return buffer[offset++]
    }

    /**
     * @brief 0 で終わる 10 進数のフィールドを読む。
     * @return 読んだ値
     * @throws RuntimeException 数字以外が含まれるとき
     */
    private fun getLong(): Long {
        var res = 0L
        while (true) {
            val b = getByte()
            if (b.toInt() == 0) return res
            if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                throw RuntimeException("Error: number format error")
            }
            res = res * 10 + (b - '0'.code.toByte())
        }
    }

    /**
     * @brief 0 で終わる UTF-8 の文字列のフィールドを読む。
     * @return 読んだ文字列
     */
    private fun getString(): String {
        var startPos = offset
        while (true) {
            for (i in startPos until allocated) {
                if (buffer[i].toInt() == 0) {
                    val res = String(buffer, offset, i - offset, Charsets.UTF_8)
                    offset = i + 1
                    return res
                }
            }
            val scanned = allocated - offset
            read()
            startPos = offset + scanned
        }
    }

    /** @brief 出力の項目の種類(NONE はディレクトリの終わり)。 */
    private enum class Type {
        NONE,
        DIR,
        FILE
    }

    /**
     * @brief 項目の種類を表す 1 文字を読む。
     * @return 項目の種類
     * @throws RuntimeException 不明な文字のとき
     */
    private fun getType(): Type = when (getByte().toInt().toChar()) {
        'D' -> Type.DIR
        'F' -> Type.FILE
        'Z' -> Type.NONE
        else -> throw RuntimeException("Error: incorrect entity type")
    }

    /**
     * @brief ネイティブスキャナを起動してマウントポイントをスキャンする。
     * @param mountPoint スキャンするマウントポイント
     * @return ツリーのルート
     * @throws com.google.android.diskusage.datasource.fast.RootDeniedException root が使えないとき
     */
    fun scan(mountPoint: MountPoint): FileSystemEntry =
        scan(NativeScannerStream.create(mountPoint.root, mountPoint.isRootRequired))

    /**
     * @brief スキャナの出力を読んでツリーを作る(ストリームは最後に閉じる)。
     *
     * 開始の 0 バイトより前の出力(su が出すメッセージなど)は読み飛ばす。
     *
     * @param stream スキャナの出力
     * @return ツリーのルート
     * @throws RuntimeException 出力の形式が正しくないとき
     */
    fun scan(stream: InputStream): FileSystemEntry = stream.use {
        input = it
        // Skip anything printed before the start marker (e.g. by su)
        while (getByte().toInt() != 0) {
            // skip
        }
        if (getType() != Type.DIR) throw RuntimeException("Error: no mount point")
        val root = scanTree()
        restoreSmallLists()
        if (offset != allocated) {
            throw RuntimeException("Error: extra data, ${allocated - offset} bytes")
        }
        root
    }

    /**
     * @brief ツリーを読む。階層が非常に深い場合もあるので、再帰せず明示的なスタックで処理する。
     * @return ツリーのルート
     */
    private fun scanTree(): FileSystemEntry {
        val stack = ArrayDeque<DirectoryBuilder>()
        stack.addLast(startDirectory(null))
        while (true) {
            val dir = stack.last()
            when (getType()) {
                Type.DIR -> stack.addLast(startDirectory(dir))
                Type.FILE -> {
                    val file = makeNode(dir.node, getString())
                    val blocks = getLong() / blockSizeIn512Bytes
                    val bytes = getLong()
                    if (blocks == 0L) continue
                    file.initSizeInBytesAndBlocks(bytes, blocks)
                    pos += file.sizeInBlocks
                    lastCreatedFile = file
                    dir.addFile(file)
                }
                Type.NONE -> {
                    val scanned = stack.removeLast().finish()
                    val parent = stack.lastOrNull() ?: return scanned.node
                    parent.addDirectory(scanned)
                }
            }
        }
    }

    /**
     * @brief ディレクトリの始まり(`D` の後)を読み、子を集める準備をする。
     * @param parent 親ディレクトリ(ルートなら null)
     * @return 新しいディレクトリ
     */
    private fun startDirectory(parent: DirectoryBuilder?): DirectoryBuilder {
        val name = getString()
        val blocks = getLong() / blockSizeIn512Bytes
        /* bytes = */ getLong()
        val node = makeNode(parent?.node, name)
        return DirectoryBuilder(node, createdNodeSize, blocks)
    }

    private companion object {
        const val BUFFER_SIZE = 65536
    }
}
