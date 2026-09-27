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
 * @file PortableFileImpl.kt
 * @brief java.io.File と Environment による PortableFile の実装。
 */
package com.google.android.diskusage.datasource.fast

import android.os.Environment
import com.google.android.diskusage.datasource.PortableFile
import com.google.android.diskusage.utils.PathHelper
import java.io.File

/**
 * @brief java.io.File と Environment による PortableFile の実装。
 *
 * 絶対パスが同じなら等しいとみなす。
 *
 * @param file 対象のディレクトリ
 */
class PortableFileImpl private constructor(private val file: File) : PortableFile {
    /** @brief エミュレートされた外部ストレージかどうか。判定できないときは false。 */
    override val isExternalStorageEmulated: Boolean
        get() = try {
            Environment.isExternalStorageEmulated(file)
        } catch (e: Exception) {
            false
        }

    /** @brief 取り外し可能なストレージかどうか。判定できないときは false。 */
    override val isExternalStorageRemovable: Boolean
        get() = try {
            Environment.isExternalStorageRemovable(file)
        } catch (e: Exception) {
            false
        }

    /** @brief 正規化したパス。取得できないときは絶対パス。 */
    override val canonicalPath: String
        get() = try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }

    /** @brief 絶対パス。 */
    override val absolutePath: String
        get() = file.absolutePath

    /** @brief ストレージの総容量(バイト)。 */
    override val totalSpace: Long
        get() = file.getTotalSpace()

    /**
     * @brief 絶対パスが同じ PortableFile なら等しいとみなす。
     * @param other 比較対象
     * @return 等しければ true
     */
    override fun equals(other: Any?): Boolean {
        if (other !is PortableFile) {
            return false
        }
        return other.absolutePath == absolutePath
    }

    /** @brief 絶対パスのハッシュ値を返す。 */
    override fun hashCode(): Int {
        return absolutePath.hashCode()
    }

    companion object {
        /**
         * @brief File から作る。
         * @param file 対象のディレクトリ
         * @return 作成したオブジェクト。file が null なら null
         */
        @JvmStatic
        fun make(file: File?): PortableFileImpl? {
            return file?.let { PortableFileImpl(it) }
        }

        /** @brief 各外部ストレージ上のアプリ専用ディレクトリ(使えないストレージは null)。 */
        @JvmStatic
        val externalAppFilesDirs: Array<PortableFile?>
            get() = PathHelper.getExternalAppFilesPaths().map { make(it) }.toTypedArray()
    }
}