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
 * @file MountPoint.kt
 * @brief スキャンできるストレージ(マウントポイント)の一覧。
 */
package com.google.android.diskusage.filesystem.mnt

import android.content.Context
import com.google.android.diskusage.R
import com.google.android.diskusage.datasource.fast.PortableFileImpl
import timber.log.Timber

/**
 * @brief スキャンできるストレージ(内部共有ストレージや SD カード)。
 * @param title 選択画面に出す名前
 * @param root ストレージのルートのパス
 * @param forceHasApps true ならアプリの容量も表示し、削除もできる(内部共有ストレージ)
 */
open class MountPoint internal constructor(
    val title: String,
    val root: String,
    private val forceHasApps: Boolean,
) {
    /** @brief スキャンに root 権限が必要かどうか。 */
    open val isRootRequired: Boolean
        get() = false

    /** @brief ファイルを削除できるかどうか。 */
    open val isDeleteSupported: Boolean
        get() = forceHasApps

    /** @brief 画面間で受け渡すための識別子。 */
    open val key: String
        get() = "storage:$root"

    /** @brief アプリの容量もツリーに含めるかどうか。 */
    open val hasApps: Boolean
        get() = forceHasApps

    companion object {
        private var mountPoints = listOf<MountPoint>()
        private var mountPointForKey = mapOf<String, MountPoint>()
        private var initialized = false

        /**
         * @brief 識別子からマウントポイントを探す(root 用のものも含む)。
         * @param context Context
         * @param key MountPoint.key の値
         * @return 見つかったマウントポイント。なければ null
         */
        fun getForKey(context: Context, key: String): MountPoint? {
            initMountPoints(context)
            return mountPointForKey[key] ?: RootMountPoint.getForKey(key)
        }

        /**
         * @brief ストレージの一覧を返す(root 用の一覧の準備も行う)。
         * @param context Context
         * @return ストレージの一覧
         */
        fun getMountPoints(context: Context): List<MountPoint> {
            initMountPoints(context)
            RootMountPoint.initMountPoints()
            return mountPoints
        }

        /**
         * @brief アプリ専用ディレクトリの場所からストレージの一覧を作る(初回だけ)。
         *
         * 取り外しできないストレージを内部共有ストレージとして扱う。
         *
         * @param context Context
         */
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

        /** @brief 一覧を破棄し、次の取得で作り直すようにする(root 用の一覧も)。 */
        fun reset() {
            mountPoints = listOf()
            mountPointForKey = mapOf()
            initialized = false
            RootMountPoint.reset()
        }
    }
}
