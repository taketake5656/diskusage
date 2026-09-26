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

package com.google.android.diskusage.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.common.ScanProgressDialog
import kotlin.concurrent.thread
import splitties.toast.toast
import timber.log.Timber

/** Called with the scanned file system tree. */
fun interface AfterLoad {
    fun run(root: FileSystemSuperRoot, isCached: Boolean)
}

/**
 * Activity which scans the file system in the background. The scan results
 * survive recreation of the activity.
 */
abstract class LoadableActivity : AppCompatActivity() {
    val handler = Handler(Looper.getMainLooper())
    var pkgRemoved: FileSystemPackage? = null

    abstract val key: String

    /** Runs on a background thread. */
    protected abstract fun scan(): FileSystemSuperRoot

    class PersistentActivityState {
        var loading: ScanProgressDialog? = null
        var root: FileSystemSuperRoot? = null
        var afterLoad: AfterLoad? = null
    }

    val persistentState: PersistentActivityState
        get() = persistentActivityStates.getOrPut(key) { PersistentActivityState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
    }

    protected fun loadFiles(afterLoad: AfterLoad, force: Boolean) {
        val state = persistentState
        Timber.d("LoadableActivity.loadFiles(), afterLoad = %s", afterLoad)

        if (force) {
            state.root = null
        }
        state.root?.let {
            afterLoad.run(it, true)
            return
        }

        val scanRunning = state.afterLoad != null
        state.afterLoad = afterLoad
        Timber.d("loadFiles: Created new progress dialog")
        val loading = ScanProgressDialog(this).apply {
            setOnCancelListener {
                state.loading = null
                finish()
            }
            setCancelable(true)
            setMax(1)
            setMessage(getString(R.string.scaning_directories))
            show()
        }
        state.loading = loading

        if (scanRunning) return

        thread(name = "Scanner") {
            val error = try {
                Timber.d("loadFiles: Running scan for %s", key)
                val newRoot = scan()
                handler.post { onScanFinished(state, newRoot) }
                return@thread
            } catch (e: OutOfMemoryError) {
                state.root = null
                state.afterLoad = null
                Timber.d("loadFiles: Out of memory!")
                handler.post {
                    state.loading?.dismiss() ?: return@post
                    handleOutOfMemory()
                }
                return@thread
            } catch (e: StackOverflowError) {
                "Filesystem is damaged."
            } catch (e: Exception) {
                Timber.e(e, "loadFiles: Native error")
                "${e.javaClass.name}:${e.message}"
            }
            state.root = null
            state.afterLoad = null
            Timber.d("loadFiles: Exception in scan!")
            handler.post {
                state.loading?.dismiss() ?: return@post
                AlertDialog.Builder(this)
                    .setTitle(error)
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private fun onScanFinished(state: PersistentActivityState, newRoot: FileSystemSuperRoot) {
        val loading = state.loading
        val afterLoad = state.afterLoad
        state.afterLoad = null
        if (loading == null) {
            Timber.d("loadFiles: No dialog, doesn't run afterLoad")
            if (newRoot.children!![0].children != null) {
                Timber.d("loadFiles: No dialog, updating root still")
                state.root = newRoot
            }
            return
        }
        if (loading.isShowing) loading.dismiss()
        state.loading = null
        Timber.d("loadFiles: Dismissed dialog")

        if (newRoot.children!![0].children == null) {
            Timber.d("loadFiles: Empty card")
            handleEmptySDCard(afterLoad!!)
            return
        }
        state.root = newRoot
        pkgRemoved = null
        Timber.d("loadFiles: Run afterLoad = %s", afterLoad)
        afterLoad!!.run(newRoot, false)
    }

    private fun handleOutOfMemory() {
        try {
            // Can fail if the main window is already closed.
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.out_of_memory))
                .setOnCancelListener { finish() }
                .show()
        } catch (t: Throwable) {
            toast("DiskUsage is out of memory. Sorry.")
        }
    }

    override fun onPause() {
        persistentState.loading?.let {
            if (it.isShowing) it.dismiss()
            Timber.d("onPause: Removed progress dialog")
            persistentState.loading = null
        }
        super.onPause()
    }

    private fun handleEmptySDCard(afterLoad: AfterLoad) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.empty_or_missing_sdcard))
            .setPositiveButton(getString(R.string.button_rescan)) { _, _ -> loadFiles(afterLoad, true) }
            .setOnCancelListener { finish() }
            .show()
    }

    private companion object {
        val persistentActivityStates = mutableMapOf<String, PersistentActivityState>()
    }
}
