/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
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
 * @file FileSystemStats.kt
 * @brief マウントポイントのブロック使用量。
 */
package com.google.android.diskusage.filesystem

import android.content.Context
import android.os.StatFs
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import timber.log.Timber

/**
 * @brief マウントポイントのブロック使用量(StatFs の値)。
 *
 * 取得できないときはブロックサイズ 512、その他は 0 になる。
 *
 * @param mountPoint 対象のマウントポイント
 */
class FileSystemStats(mountPoint: MountPoint) {
    /** @brief ブロックサイズ(バイト)。 */
    val blockSize: Long
    /** @brief アプリが使える空きブロック数。 */
    val freeBlocks: Long
    /** @brief 使用中のブロック数(総数 - 空き)。 */
    val busyBlocks: Long
    /** @brief ブロックの総数。 */
    val totalBlocks: Long

    init {
        val stats = try {
            StatFs(mountPoint.root)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Failed to get filesystem stats for %s", mountPoint.root)
            null
        }
        if (stats != null) {
            blockSize = stats.blockSizeLong
            freeBlocks = stats.availableBlocksLong
            totalBlocks = stats.blockCountLong
            busyBlocks = totalBlocks - freeBlocks
        } else {
            blockSize = 512
            freeBlocks = 0
            totalBlocks = 0
            busyBlocks = 0
        }
    }

    /**
     * @brief 「使用量 / 総容量」の表示文字列を作る。
     * @param context 文字列リソースの取得に使う Context
     * @return 表示文字列。容量が取得できていなければ「不明」の文字列
     */
    fun formatUsageInfo(context: Context): String {
        if (totalBlocks == 0L) return context.getString(R.string.usage_info_unknown)
        return context.getString(
            R.string.usage_info,
            FileSystemEntry.calcSizeString(busyBlocks * blockSize),
            FileSystemEntry.calcSizeString(totalBlocks * blockSize),
        )
    }
}
