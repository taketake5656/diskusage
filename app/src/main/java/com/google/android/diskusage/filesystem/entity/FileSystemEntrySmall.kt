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
 * @file FileSystemEntrySmall.kt
 * @brief 小さなファイルをまとめた項目(「<N 個のファイル>」)。
 */
package com.google.android.diskusage.filesystem.entity

/**
 * @brief 個別に表示するには小さすぎるファイルやディレクトリを、ディレクトリごとにまとめた項目。
 *
 * ヒープの予算を超えたときに TreeScanner が作る。
 *
 * @param parent 親ディレクトリの項目
 * @param name 表示名(「<N 個のファイル>」)
 * @param numFiles まとめたファイルの数
 */
class FileSystemEntrySmall(
    parent: FileSystemEntry?,
    name: String,
    override val numFiles: Int,
) : FileSystemEntry(parent, name) {

    /**
     * @brief 同じ内容の空の項目を作る(コピー用)。
     * @return 新しい項目
     */
    override fun create(): FileSystemEntry = FileSystemEntrySmall(null, name, numFiles)

    /** @brief 検索結果には含めない。 @return 常に null */
    override fun filter(pattern: CharSequence, blockSize: Long): FileSystemEntry? = null
}
