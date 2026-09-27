/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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
 * @file FileSystemPackage.kt
 * @brief インストール済みアプリを表す項目。
 */
package com.google.android.diskusage.filesystem.entity

import android.content.pm.ApplicationInfo
import timber.log.Timber

/**
 * @brief インストール済みアプリ。子として apk・data・cache と、共有ストレージ上のアプリのディレクトリを持つ。
 * @param name アプリ名
 * @param pkg パッケージ名
 * @param codeSize apk の容量(バイト)
 * @param dataSize データの容量(キャッシュを除く、バイト)
 * @param cacheSize キャッシュの容量(バイト)
 * @param flags ApplicationInfo.flags
 */
class FileSystemPackage private constructor(
    name: String,
    val pkg: String,
    private var codeSize: Long,
    private var dataSize: Long,
    private var cacheSize: Long,
    private val flags: Int,
) : FileSystemEntry(null, name) {
    private val publicChildren = mutableListOf<FileSystemRoot>()

    /** @brief 共有ストレージ上のディレクトリが、アプリのどの容量に含まれるか。 */
    enum class ChildType {
        CODE,
        DATA,
        CACHE
    }

    /**
     * @brief 子の項目(apk・data・cache と共有ストレージ上のディレクトリ)を作り、合計サイズを設定する。
     * @param blockSize 表示のブロックサイズ
     */
    fun applyFilter(blockSize: Long) {
        clearSizeStringCache()
        val entries = publicChildren + listOf(
            makeNode(null, "apk").initSizeInBytes(codeSize, blockSize),
            makeNode(null, "data").initSizeInBytes(dataSize, blockSize),
            makeNode(null, "cache").initSizeInBytes(cacheSize, blockSize),
        )
        setSizeInBlocks(entries.sumOf { it.sizeInBlocks }, blockSize)
        entries.forEach { it.parent = this }
        children = entries.sortedWith(COMPARE).toTypedArray()
    }

    /**
     * @brief 同じ内容の空の項目を作る(コピー用)。
     * @return 新しい項目
     */
    override fun create(): FileSystemEntry =
        FileSystemPackage(name, pkg, codeSize, dataSize, cacheSize, flags)

    /**
     * @brief 共有ストレージ上のアプリのディレクトリ(Android/data など)を子に加える。
     *
     * 二重に数えないように、そのサイズを該当する容量から差し引く。
     *
     * @param child 追加するディレクトリ
     * @param type どの容量に含まれるか
     * @param blockSize ブロックサイズ
     */
    fun addPublicChild(child: FileSystemRoot, type: ChildType, blockSize: Long) {
        publicChildren += child
        val size = child.sizeInBlocks * blockSize
        when (type) {
            ChildType.CODE -> codeSize = subtract(codeSize, size, "Code")
            ChildType.DATA -> dataSize = subtract(dataSize, size, "Data")
            ChildType.CACHE -> cacheSize = subtract(cacheSize, size, "Cache")
        }
    }

    /**
     * @brief 容量を差し引く。負になるときは 0 にする。
     * @param total 元の容量
     * @param size 差し引く容量
     * @param what ログに出す容量の種類
     * @return 差し引いた容量
     */
    private fun subtract(total: Long, size: Long, what: String): Long {
        val result = total - size
        if (result < 0) {
            Timber.d("addPublicChild: %s size negative %s for %s", what, result, pkg)
            return 0
        }
        return result
    }

    companion object {
        /**
         * @brief アプリの項目を作る。
         *
         * 更新されていないシステムアプリと外部ストレージ上のアプリは apk の容量を 0 にする。
         * データの容量からはキャッシュを除く。
         *
         * @param name アプリ名
         * @param pkg パッケージ名
         * @param codeSize apk の容量(バイト)
         * @param dataSize データの容量(キャッシュを含む、バイト)
         * @param cacheSize キャッシュの容量(バイト)
         * @param flags ApplicationInfo.flags
         * @return 作成した項目
         */
        fun make(
            name: String, pkg: String, codeSize: Long, dataSize: Long, cacheSize: Long, flags: Int,
        ): FileSystemPackage {
            val isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            // TODO: not sure what happens with apps on external storage
            val onExternalStorage = (flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
            return FileSystemPackage(
                name, pkg,
                codeSize = if (isSystemApp || onExternalStorage) 0 else codeSize,
                dataSize = dataSize - cacheSize,
                cacheSize = cacheSize,
                flags = flags,
            )
        }
    }
}
