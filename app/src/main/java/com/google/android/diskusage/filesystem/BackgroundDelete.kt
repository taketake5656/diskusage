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
 * @file BackgroundDelete.kt
 * @brief ファイルやディレクトリの削除。
 */
package com.google.android.diskusage.filesystem

import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import com.google.android.diskusage.R
import com.google.android.diskusage.core.Scanner
import com.google.android.diskusage.datasource.fast.LegacyFileImpl
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.ui.DiskUsage
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import splitties.resources.appStr
import splitties.toast.longToast
import splitties.toast.toast
import timber.log.Timber

/**
 * @brief ディレクトリを進捗ダイアログ付きでバックグラウンドで削除する。
 * @param diskUsage メイン画面
 * @param entry 削除する項目
 * @param file 削除するディレクトリ
 */
class BackgroundDelete private constructor(
    private val diskUsage: DiskUsage,
    private val entry: FileSystemEntry,
    private val file: File,
) {
    /** @brief 削除の結果。 */
    private enum class Status {
        SUCCESS,
        FAILED,
        CANCELED
    }

    private val path = entry.path2()
    private var dialog: AlertDialog? = null

    @Volatile
    private var cancelDeletion = false
    private var numDeletedDirectories = 0
    private var numDeletedFiles = 0

    private val fileSystemState
        get() = diskUsage.fileSystemState!!

    /** @brief 進捗ダイアログを表示し、削除のスレッドを開始する。 */
    private fun start() {
        val padding = diskUsage.resources.getDimensionPixelSize(R.dimen.default_app_icon_size) / 2
        dialog = AlertDialog.Builder(diskUsage)
            .setMessage(appStr(R.string.deleting_path, path))
            .setView(ProgressBar(diskUsage, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                setPadding(padding, 0, padding, 0)
            })
            .setPositiveButton(R.string.button_background, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelDeletion = true }
            .setOnDismissListener { dialog = null }
            .show()
        thread(name = "BackgroundDelete") {
            val status = deleteRecursively(file)
            // FIXME: use notification object when backgrounded
            diskUsage.handler.post { onFinished(status) }
        }
    }

    /**
     * @brief 削除の完了を受け取る(メインスレッド)。
     *
     * ツリーから項目を取り除き、削除しきれなかったときは残った分をスキャンし直して戻す。
     *
     * @param status 削除の結果
     */
    private fun onFinished(status: Status) {
        try {
            dialog?.dismiss()
        } catch (e: Exception) {
            // ignore exception
        }
        fileSystemState.removeEntry(entry)
        if (status != Status.SUCCESS) {
            restore()
        }
        notifyUser(status)
    }

    /** @brief 削除しきれずに残ったディレクトリを Java 版スキャナで読み直し、ツリーに戻す。 */
    private fun restore() {
        Timber.d("restore started for %s", path)
        val mountPoint = MountPoint.getForKey(diskUsage, diskUsage.key) ?: return
        val displayBlockSize = fileSystemState.masterRoot.displayBlockSize
        try {
            // FIXME: hacked allocatedBlocks and heap size
            val newEntry = Scanner(20, displayBlockSize, 0, 4)
                .scan(LegacyFileImpl.createRoot(mountPoint.root + "/" + path))
            // FIXME: may be problems in case of two deletions
            entry.parent!!.insert(newEntry, displayBlockSize)
            fileSystemState.restore()
            Timber.d("restore: Restoring undeleted: %s %s", newEntry.name, newEntry.sizeString())
        } catch (e: IOException) {
            Timber.d(e, "Failed to restore")
        }
    }

    /**
     * @brief 削除したディレクトリとファイルの数をトーストで知らせる。
     * @param status 削除の結果
     */
    private fun notifyUser(status: Status) {
        Timber.d("notifyUser: Delete: status = %s directories %s files %s",
            status, numDeletedDirectories, numDeletedFiles)
        val message = when (status) {
            Status.SUCCESS -> R.string.deleted_n_directories_and_n_files
            Status.CANCELED -> R.string.deleted_n_directories_and_files_and_canceled
            Status.FAILED -> R.string.deleted_n_directories_and_n_files_and_failed
        }
        longToast(appStr(message, numDeletedDirectories, numDeletedFiles))
    }

    /**
     * @brief ファイルまたはディレクトリを中身ごと削除する。失敗か取り消しの時点で止める。
     * @param file 削除する対象
     * @return 削除の結果
     */
    private fun deleteRecursively(file: File): Status {
        if (cancelDeletion) return Status.CANCELED
        val isDirectory = file.isDirectory
        if (isDirectory) {
            val files = file.listFiles() ?: return Status.FAILED
            for (child in files) {
                val status = deleteRecursively(child)
                if (status != Status.SUCCESS) return status
            }
        }
        if (!file.delete()) {
            return Status.FAILED
        }
        if (isDirectory) numDeletedDirectories++ else numDeletedFiles++
        return Status.SUCCESS
    }

    companion object {
        /**
         * @brief 項目を削除する。
         *
         * ストレージ全体を含む削除は取り消す。ファイルはその場で削除し、
         * ディレクトリはバックグラウンドで削除する。
         *
         * @param diskUsage メイン画面
         * @param entry 削除する項目
         */
        fun startDelete(diskUsage: DiskUsage, entry: FileSystemEntry) {
            val path = entry.path2()
            val deleteRoot = entry.absolutePath()
            val file = File(deleteRoot)
            val state = diskUsage.fileSystemState ?: return
            if (MountPoint.getMountPoints(diskUsage).any { "${it.root}/".startsWith("$deleteRoot/") }) {
                longToast(R.string.delete_whole_storage_canceled)
                return
            }
            if (!file.exists()) {
                longToast(appStr(R.string.path_doesnt_exist, path))
                state.removeEntry(entry)
                return
            }
            if (file.isFile) {
                if (file.delete()) {
                    toast(R.string.file_deleted)
                    state.removeEntry(entry)
                } else {
                    toast(R.string.error_file_wasnt_deleted)
                }
                return
            }
            BackgroundDelete(diskUsage, entry, file).start()
        }
    }
}
