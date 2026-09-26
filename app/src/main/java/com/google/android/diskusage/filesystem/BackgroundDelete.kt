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

/** Deletes a directory in the background, showing progress. */
class BackgroundDelete private constructor(
    private val diskUsage: DiskUsage,
    private val entry: FileSystemEntry,
    private val file: File,
) {
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

    private fun onFinished(status: Status) {
        try {
            dialog?.dismiss()
        } catch (e: Exception) {
            // ignore exception
        }
        fileSystemState.removeInRenderThread(entry)
        if (status != Status.SUCCESS) {
            restore()
            fileSystemState.requestRepaint()
            fileSystemState.requestRepaintGPU()
        }
        notifyUser(status)
    }

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
        fun startDelete(diskUsage: DiskUsage, entry: FileSystemEntry) {
            val path = entry.path2()
            val deleteRoot = entry.absolutePath()
            val file = File(deleteRoot)
            val state = diskUsage.fileSystemState ?: return
            if (MountPoint.getMountPoints(diskUsage).any { "${it.root}/".startsWith("$deleteRoot/") }) {
                longToast("This delete operation will erase entire storage - canceled.")
                return
            }
            if (!file.exists()) {
                longToast(appStr(R.string.path_doesnt_exist, path))
                state.removeInRenderThread(entry)
                return
            }
            if (file.isFile) {
                if (file.delete()) {
                    toast(R.string.file_deleted)
                    state.removeInRenderThread(entry)
                } else {
                    toast(R.string.error_file_wasnt_deleted)
                }
                return
            }
            BackgroundDelete(diskUsage, entry, file).start()
        }
    }
}
