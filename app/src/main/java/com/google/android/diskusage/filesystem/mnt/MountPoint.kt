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

package com.google.android.diskusage.filesystem.mnt

import android.content.Context
import com.google.android.diskusage.R
import com.google.android.diskusage.datasource.fast.PortableFileImpl
import timber.log.Timber

/** Storage volume which can be scanned. */
open class MountPoint internal constructor(
    val title: String,
    val root: String,
    private val forceHasApps: Boolean,
) {
    open val isRootRequired: Boolean
        get() = false

    open val isDeleteSupported: Boolean
        get() = forceHasApps

    open val key: String
        get() = "storage:$root"

    open val hasApps: Boolean
        get() = forceHasApps

    companion object {
        private var mountPoints = listOf<MountPoint>()
        private var mountPointForKey = mapOf<String, MountPoint>()
        private var initialized = false

        fun getForKey(context: Context, key: String): MountPoint? {
            initMountPoints(context)
            return mountPointForKey[key] ?: RootMountPoint.getForKey(key)
        }

        fun getMountPoints(context: Context): List<MountPoint> {
            initMountPoints(context)
            RootMountPoint.initMountPoints()
            return mountPoints
        }

        private fun initMountPoints(context: Context) {
            if (initialized) return
            initialized = true

            val appFilesSuffix = "/Android/data/${context.packageName}/files"
            mountPoints = PortableFileImpl.externalAppFilesDirs.filterNotNull().map { dir ->
                val path = dir.absolutePath.replaceFirst(appFilesSuffix, "")
                Timber.d("MountPoint.initMountPoints: mountpoint %s", path)
                val internal = !dir.isExternalStorageRemovable
                val title = if (internal) context.getString(R.string.storage_card) else path
                MountPoint(title, path, internal)
            }
            mountPointForKey = mountPoints.associateBy { it.key }
        }

        fun reset() {
            mountPoints = listOf()
            mountPointForKey = mapOf()
            initialized = false
            RootMountPoint.reset()
        }
    }
}
