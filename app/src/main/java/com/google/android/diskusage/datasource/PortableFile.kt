/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 * Copyright (C) 2024 WhiredPlanck
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
 * @file PortableFile.kt
 * @brief ストレージのルートディレクトリを表すインターフェース。
 */
package com.google.android.diskusage.datasource

/**
 * @brief ストレージ(内部共有ストレージや SD カード)のルートディレクトリの情報。
 */
interface PortableFile {
    /** @brief 内部ストレージ上にエミュレートされた外部ストレージかどうか。 */
    val isExternalStorageEmulated: Boolean

    /** @brief 取り外し可能なストレージ(SD カードなど)かどうか。 */
    val isExternalStorageRemovable: Boolean

    /** @brief 正規化したパス。IOException のときは絶対パスで代用する。 */
    val canonicalPath: String

    /** @brief 絶対パス。 */
    val absolutePath: String

    /** @brief ストレージの総容量(バイト)。 */
    val totalSpace: Long
}
