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
 * @file LegacyFile.kt
 * @brief Java 版スキャナが走査するファイルのインターフェース。
 */
package com.google.android.diskusage.datasource

import java.io.IOException

/**
 * @brief Java 版スキャナ(core/Scanner)が扱うファイルまたはディレクトリ。
 */
interface LegacyFile {
    /** @brief ファイル名。 */
    val name: String?

    /** @brief 正規化したパス。 */
    @get:Throws(IOException::class)
    val canonicalPath: String?

    /** @brief シンボリックリンクかどうか。 */
    val isLink: Boolean

    /** @brief 通常のファイルかどうか。 */
    val isFile: Boolean

    /**
     * @brief ファイルのサイズを返す。
     * @return サイズ(バイト)
     */
    fun length(): Long

    /**
     * @brief ディレクトリ内の項目を返す。
     * @return 子の一覧。ディレクトリでないか読めないときは null
     */
    fun listFiles(): Array<LegacyFile>?

    /**
     * @brief ディレクトリ内の項目の名前を返す。
     * @return 名前の一覧。ディレクトリでないか読めないときは null
     */
    fun list(): Array<String>?

    /**
     * @brief 名前を指定して子の項目を返す。
     * @param string 子の名前
     * @return 子の項目
     */
    fun getChild(string: String): LegacyFile?
}