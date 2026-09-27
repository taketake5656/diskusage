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
 * @file FileSystemSuperRoot.kt
 * @brief ツリー全体の最上位にある、表示されない項目。
 */
package com.google.android.diskusage.filesystem.entity

/**
 * @brief 表示されない最上位の項目。子は 1 つだけで、それが表示上のルートになる。
 * @param displayBlockSize 表示に使うブロックサイズ(バイト)
 */
class FileSystemSuperRoot(val displayBlockSize: Long) :
    FileSystemSpecial("", 0, displayBlockSize) {

    /**
     * @brief 同じブロックサイズの空の項目を作る(コピー用)。
     * @return 新しい項目
     */
    override fun create(): FileSystemEntry = FileSystemSuperRoot(displayBlockSize)

    /**
     * @brief 子の項目だけを検索する(自身の名前は照合しない)。
     * @param pattern 検索文字列
     * @param blockSize 表示のブロックサイズ
     * @return 一致した項目を含むツリー。なければ null
     */
    // don't match name
    override fun filter(pattern: CharSequence, blockSize: Long): FileSystemEntry? =
        filterChildren(pattern, blockSize)

    /**
     * @brief 絶対パスに対応する項目を、子のルートから探す。
     * @param path 探す絶対パス
     * @return 見つかった項目。なければ null
     */
    fun getByAbsolutePath(path: String): FileSystemEntry? =
        children!!.firstNotNullOfOrNull { (it as? FileSystemRoot)?.getByAbsolutePath(path) }

    /**
     * @brief 表示上のルートからの相対パスで項目を探す。
     * @param path 相対パス
     * @param exactMatch 完全一致で探すかどうか(現在はどちらでも完全一致で探す)
     * @return 見つかった項目。なければ null
     */
    override fun getEntryByName(path: String, exactMatch: Boolean): FileSystemEntry? =
        children!![0].getEntryByName(path, exactMatch)
}
