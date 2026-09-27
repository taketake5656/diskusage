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
 * @file IOHelper.kt
 * @brief システムファイルの読み込みヘルパー。
 */
package com.google.android.diskusage.utils

import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * @brief システムファイルの読み込みヘルパー。
 */
object IOHelper {
    private const val PROC_MOUNTS = "/proc/mounts"

    /**
     * @brief マウント一覧(/proc/mounts)を読むリーダーを開く。
     * @return 1 行 1 マウントのリーダー。呼び出し側で閉じること
     * @throws IOException 開けなかったとき
     */
    @JvmStatic
    @Throws(IOException::class)
    fun getProcMountsReader(): BufferedReader
        = BufferedReader(InputStreamReader(FileInputStream(PROC_MOUNTS)))

}