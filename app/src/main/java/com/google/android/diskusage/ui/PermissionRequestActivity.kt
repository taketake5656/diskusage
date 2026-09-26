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

package com.google.android.diskusage.ui

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.android.diskusage.R
import com.google.android.diskusage.databinding.ActivityCommonBinding
import com.google.android.diskusage.filesystem.mnt.MountPoint
import splitties.toast.toast
import timber.log.Timber

/** Asks for the missing permissions one by one, then opens [DiskUsage]. */
class PermissionRequestActivity : ComponentActivity() {
    private lateinit var mountPoint: MountPoint
    private var storageRequested = false
    private var usageAccessRequested = false

    private val diskUsage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            setResult(0, result.data)
            finish()
        }

    private val settings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requestNextPermission()
        }

    private val storagePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestNextPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityCommonBinding.inflate(layoutInflater).root)

        // Just close instead of crashing later
        val key = intent.getStringExtra(DiskUsage.KEY_KEY)
        mountPoint = key?.let { MountPoint.getForKey(this, it) } ?: run {
            finish()
            return
        }
        if (savedInstanceState != null) {
            // Waiting for a result of a started activity
            storageRequested = savedInstanceState.getBoolean(STORAGE_REQUESTED_KEY)
            usageAccessRequested = savedInstanceState.getBoolean(USAGE_ACCESS_REQUESTED_KEY)
            return
        }
        requestNextPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STORAGE_REQUESTED_KEY, storageRequested)
        outState.putBoolean(USAGE_ACCESS_REQUESTED_KEY, usageAccessRequested)
    }

    private fun requestNextPermission() {
        if (!isExternalStorageAccessGranted()) {
            if (!storageRequested) {
                storageRequested = true
                requestExternalStoragePermission()
                return
            }
            toast(R.string.dialog_external_storage_access_error)
        }

        if (mountPoint.hasApps && !isUsageAccessGranted() && !usageAccessRequested) {
            usageAccessRequested = true
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_usage_access_title)
                .setMessage(R.string.dialog_usage_access_desc)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    settings.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> forwardToDiskUsage() }
                .setOnCancelListener { forwardToDiskUsage() }
                .show()
            return
        }

        forwardToDiskUsage()
    }

    private fun forwardToDiskUsage() {
        diskUsage.launch(Intent(this, DiskUsage::class.java).apply {
            putExtra(DiskUsage.KEY_KEY, intent.getStringExtra(DiskUsage.KEY_KEY))
            putExtra(DiskUsage.STATE_KEY, intent.getBundleExtra(DiskUsage.STATE_KEY))
        })
    }

    private fun isExternalStorageAccessGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            STORAGE_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    private fun requestExternalStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            storagePermissions.launch(STORAGE_PERMISSIONS)
            return
        }
        try {
            settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData("package:$packageName".toUri()))
        } catch (e: ActivityNotFoundException) {
            Timber.d(e, "failed to obtain all files access")
            settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun isUsageAccessGranted(): Boolean {
        val mode = getSystemService<AppOpsManager>()!!
            .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private companion object {
        const val STORAGE_REQUESTED_KEY = "storage_requested"
        const val USAGE_ACCESS_REQUESTED_KEY = "usage_access_requested"
        val STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }
}
