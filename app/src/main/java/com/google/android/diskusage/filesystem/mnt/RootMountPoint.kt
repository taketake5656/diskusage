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

import com.google.android.diskusage.utils.IOHelper
import timber.log.Timber

/** Any mount point of the device, scanned with root permissions. */
class RootMountPoint private constructor(root: String) : MountPoint(root, root, false) {
    override val isRootRequired: Boolean
        get() = true

    override val hasApps: Boolean
        get() = false

    override val isDeleteSupported: Boolean
        get() = false

    override val key: String
        get() = "rooted:$root"

    companion object {
        private var rootedMountPoints = listOf<MountPoint>()
        private var rootedMountPointForKey = mapOf<String, MountPoint>()
        private var initialized = false

        /** Sum of the line lengths of /proc/mounts, to detect changes. */
        var checksum = 0
            private set

        fun getRootedMountPoints(): List<MountPoint> {
            initMountPoints()
            return rootedMountPoints
        }

        fun getForKey(key: String): MountPoint? {
            initMountPoints()
            return rootedMountPointForKey[key]
        }

        fun initMountPoints() {
            if (initialized) return
            initialized = true

            try {
                checksum = 0
                // Shortest mount point of each device: bind mounts show the same files again
                val mountPointForDevice = LinkedHashMap<String, String>()
                IOHelper.getProcMountsReader().useLines { lines ->
                    for (line in lines) {
                        checksum += line.length
                        val parts = line.split(Regex(" +"))
                        if (parts.size < 3) continue
                        val (device, mountPoint, type) = parts
                        if (!isStorage(mountPoint, type)) continue
                        val known = mountPointForDevice[device]
                        if (known == null || mountPoint.length < known.length) {
                            mountPointForDevice[device] = mountPoint
                        }
                    }
                }
                Timber.d("initMountPoints: %s", mountPointForDevice)
                val mountPoints = mountPointForDevice.values.map { RootMountPoint(it) }
                rootedMountPoints = mountPoints
                rootedMountPointForKey = mountPoints.associateBy { it.key }
            } catch (e: Exception) {
                Timber.e(e, "initMountPoints: Failed to get mount points")
            }
        }

        /** File systems without files stored on the device. */
        private val virtualTypes = setOf(
            "autofs", "binder", "binderfs", "bpf", "cgroup", "cgroup2", "configfs", "debugfs",
            "devpts", "devtmpfs", "efivarfs", "functionfs", "fuse", "fusectl", "incremental-fs",
            "mqueue", "nsfs", "overlay", "proc", "pstore", "rootfs", "sdcardfs", "securityfs",
            "selinuxfs", "sysfs", "tmpfs", "tracefs",
        )

        /** Mount point prefixes of the many small APEX images. */
        private val ignoredPrefixes = listOf("/apex/", "/bootstrap-apex/", "/mnt/asec/")

        private fun isStorage(mountPoint: String, type: String): Boolean =
            type !in virtualTypes && ignoredPrefixes.none { mountPoint.startsWith(it) }

        fun reset() {
            rootedMountPoints = listOf()
            rootedMountPointForKey = mapOf()
            initialized = false
        }
    }
}
