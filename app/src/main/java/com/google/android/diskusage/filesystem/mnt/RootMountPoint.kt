/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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
 * @file RootMountPoint.kt
 * @brief root 権限でスキャンする端末のマウントポイント。
 */
package com.google.android.diskusage.filesystem.mnt

import com.google.android.diskusage.utils.IOHelper
import timber.log.Timber

/**
 * @brief root 権限でスキャンする、端末の任意のマウントポイント。
 *
 * アプリの容量は含めず、削除もできない。
 *
 * @param root マウントポイントのパス(表示名にも使う)
 */
class RootMountPoint private constructor(root: String) : MountPoint(root, root, false) {
    /** @brief 常に root 権限が必要。 */
    override val isRootRequired: Boolean
        get() = true

    /** @brief アプリの容量は含めない。 */
    override val hasApps: Boolean
        get() = false

    /** @brief 削除はできない。 */
    override val isDeleteSupported: Boolean
        get() = false

    /** @brief 画面間で受け渡すための識別子。 */
    override val key: String
        get() = "rooted:$root"

    companion object {
        private var rootedMountPoints = listOf<MountPoint>()
        private var rootedMountPointForKey = mapOf<String, MountPoint>()
        private var initialized = false

        /** @brief /proc/mounts の各行の長さの合計。マウントの変化を検出するのに使う。 */
        var checksum = 0
            private set

        /**
         * @brief root 用のマウントポイントの一覧を返す。
         * @return マウントポイントの一覧
         */
        fun getRootedMountPoints(): List<MountPoint> {
            initMountPoints()
            return rootedMountPoints
        }

        /**
         * @brief 識別子から root 用のマウントポイントを探す。
         * @param key MountPoint.key の値
         * @return 見つかったマウントポイント。なければ null
         */
        fun getForKey(key: String): MountPoint? {
            initMountPoints()
            return rootedMountPointForKey[key]
        }

        /**
         * @brief /proc/mounts から、ストレージのマウントポイントの一覧を作る(初回だけ)。
         *
         * 仮想ファイルシステムと APEX などは除く。バインドマウントは同じファイルを
         * 重ねて見せるだけなので、デバイスごとに最も短いマウントポイントだけを残す。
         */
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

        /** @brief 端末に保存されたファイルを持たない(仮想の)ファイルシステムの種類。 */
        private val virtualTypes = setOf(
            "autofs", "binder", "binderfs", "bpf", "cgroup", "cgroup2", "configfs", "debugfs",
            "devpts", "devtmpfs", "efivarfs", "functionfs", "fuse", "fusectl", "incremental-fs",
            "mqueue", "nsfs", "overlay", "proc", "pstore", "rootfs", "sdcardfs", "securityfs",
            "selinuxfs", "sysfs", "tmpfs", "tracefs",
        )

        /** @brief 多数ある小さな APEX イメージなどのマウントポイントの接頭辞。 */
        private val ignoredPrefixes = listOf("/apex/", "/bootstrap-apex/", "/mnt/asec/")

        /**
         * @brief 一覧に出すストレージかどうかを判定する。
         * @param mountPoint マウントポイントのパス
         * @param type ファイルシステムの種類
         * @return 仮想ファイルシステムでなく、除外する接頭辞にも当たらなければ true
         */
        private fun isStorage(mountPoint: String, type: String): Boolean =
            type !in virtualTypes && ignoredPrefixes.none { mountPoint.startsWith(it) }

        /** @brief 一覧を破棄し、次の取得で作り直すようにする。 */
        fun reset() {
            rootedMountPoints = listOf()
            rootedMountPointForKey = mapOf()
            initialized = false
        }
    }
}
