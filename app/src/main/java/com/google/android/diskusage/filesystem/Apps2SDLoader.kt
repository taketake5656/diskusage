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
 * @file Apps2SDLoader.kt
 * @brief アプリごとのストレージ使用量の読み込み。
 */
package com.google.android.diskusage.filesystem

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.ui.DiskUsage
import java.io.IOException
import timber.log.Timber

/**
 * @brief アプリごとのストレージ使用量を読み込む。使用状況へのアクセス権限が必要。
 * @param diskUsage 進捗ダイアログを持つメイン画面
 */
@RequiresApi(Build.VERSION_CODES.O)
class Apps2SDLoader(private val diskUsage: DiskUsage) {
    @Volatile
    private var lastAppName: CharSequence = ""

    @Volatile
    private var numLoadedPackages = 0

    /**
     * @brief 使用状況の統計に現れるアプリの容量を StorageStatsManager で取得する。
     *
     * ワーカースレッドから呼ぶ。読み込み中は 50 ms ごとに進捗ダイアログを更新する
     * (進捗バーの残りの段階をすべて使う)。
     *
     * @param blockSize 表示のブロックサイズ
     * @return アプリの項目(サイズの大きい順)
     */
    fun load(blockSize: Long): List<FileSystemPackage> {
        val usageStatsManager = diskUsage.getSystemService<UsageStatsManager>()!!
        val storageStatsManager = diskUsage.getSystemService<StorageStatsManager>()!!
        val packageManager = diskUsage.packageManager
        val packages = usageStatsManager
            .queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, 0, System.currentTimeMillis())
            .mapTo(HashSet()) { it.packageName }
        Timber.d("load: Stats size = %s", packages.size)

        val handler = diskUsage.handler
        val progressUpdater = object : Runnable {
            private var phaseStarted = false

            override fun run() {
                diskUsage.persistentState.loading?.let { dialog ->
                    if (!phaseStarted) {
                        dialog.startPhase(1.0)
                        phaseStarted = true
                    }
                    dialog.setMax(packages.size.toLong())
                    dialog.setProgress(numLoadedPackages.toLong(), lastAppName)
                }
                handler.postDelayed(this, 50)
            }
        }
        handler.post(progressUpdater)
        try {
            return packages.mapNotNull { pkg ->
                Timber.d("app: %s", pkg)
                try {
                    val info = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                    val appName = info.loadLabel(packageManager).toString()
                    lastAppName = appName
                    val stats = storageStatsManager.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                    Timber.d("stats: %s %s", stats.appBytes, stats.dataBytes)
                    FileSystemPackage.make(
                        appName, pkg, stats.appBytes, stats.dataBytes, stats.cacheBytes, info.flags,
                    ).also {
                        it.applyFilter(blockSize)
                        numLoadedPackages++
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    Timber.d(e, "Failed to get package")
                    null
                } catch (e: IOException) {
                    Timber.d(e, "Failed to get package stats")
                    null
                }
            }.sortedWith(FileSystemEntry.COMPARE)
        } finally {
            handler.removeCallbacks(progressUpdater)
        }
    }
}
