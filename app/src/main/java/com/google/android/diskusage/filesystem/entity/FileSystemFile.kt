/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 * Copyright (C) 2022 WhiredPlanck
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
 * @file FileSystemFile.kt
 * @brief ツリー上の通常のファイルを表す項目。
 */
package com.google.android.diskusage.filesystem.entity

/**
 * @brief ツリー上の通常のファイル(子を持たない項目)。
 */
class FileSystemFile private constructor(parent: FileSystemEntry?, name: String) :
    FileSystemEntry(parent, name) {

    /** @brief ファイルは常に削除できる。 */
    override val isDeletable: Boolean
        get() = true

    /**
     * @brief 同じ名前の空の項目を作る(コピー用)。
     * @return 親を持たない新しい項目
     */
    override fun create(): FileSystemEntry = FileSystemFile(null, name)

    companion object {
        /**
         * @brief ファイルの項目を作る。
         * @param parent 親ディレクトリの項目
         * @param name ファイル名
         * @return 作成した項目
         */
        fun makeNode(parent: FileSystemEntry?, name: String): FileSystemEntry =
            FileSystemFile(parent, name)
    }
}
