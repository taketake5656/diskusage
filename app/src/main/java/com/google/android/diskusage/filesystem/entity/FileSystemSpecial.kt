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
 * @file FileSystemSpecial.kt
 * @brief 実在するファイルに対応しない特別な項目の基底クラス。
 */
package com.google.android.diskusage.filesystem.entity

/**
 * @brief 実在するファイルに対応しない項目(空き容量、アプリ、まとめた項目など)の基底クラス。
 * @param name 表示名
 * @param size 容量(バイト)
 * @param blockSize ブロックサイズ(バイト)
 */
open class FileSystemSpecial(name: String, size: Long, blockSize: Long) :
    FileSystemEntry(null, name) {

    init {
        initSizeInBytes(size, blockSize)
    }

    /**
     * @brief 子の項目だけを検索する。
     * @param pattern 検索文字列
     * @param blockSize 表示のブロックサイズ
     * @return 一致した項目を含むツリー。なければ null
     */
    override fun filter(pattern: CharSequence, blockSize: Long): FileSystemEntry? =
        filterChildren(pattern, blockSize)

    /**
     * @brief 同じ名前の空の項目を作る(コピー用。サイズは仮の値)。
     * @return 新しい項目
     */
    // dummy values for size
    override fun create(): FileSystemEntry = FileSystemSpecial(name, 0, 512)
}
