/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2022 WhiredPlanck
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
 * @file PathHelper.kt
 * @brief ストレージのパスを取得するヘルパー。
 */
package com.google.android.diskusage.utils

import com.google.android.diskusage.DiskUsageApplication
import java.io.File

/**
 * @brief ストレージのパスを取得するヘルパー。
 */
object PathHelper {
    /**
     * @brief 各外部ストレージ上のアプリ専用ディレクトリの一覧を返す。
     *
     * 内部共有ストレージと SD カードなど、ストレージごとに 1 つずつ返る。
     * ストレージのルートを探す手がかりに使う。
     *
     * @return ストレージごとのアプリ専用ディレクトリ(取り外し中のストレージは null の場合がある)
     */
    @JvmStatic
    fun getExternalAppFilesPaths(): Array<out File> {
        return DiskUsageApplication.getInstance()
            .getExternalFilesDirs(null)
    }
}