/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
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

package com.google.android.diskusage.filesystem

import android.content.Context
import android.os.StatFs
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import timber.log.Timber

/** Block usage of a mount point. */
class FileSystemStats(mountPoint: MountPoint) {
    val blockSize: Long
    val freeBlocks: Long
    val busyBlocks: Long
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

    fun formatUsageInfo(context: Context): String {
        if (totalBlocks == 0L) return context.getString(R.string.usage_info_unknown)
        return context.getString(
            R.string.usage_info,
            FileSystemEntry.calcSizeString(busyBlocks * blockSize),
            FileSystemEntry.calcSizeString(totalBlocks * blockSize),
        )
    }
}
