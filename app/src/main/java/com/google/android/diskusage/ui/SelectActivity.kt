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
 * @file SelectActivity.kt
 * @brief 起動時の、表示するストレージの選択画面。
 */
package com.google.android.diskusage.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.diskusage.R
import com.google.android.diskusage.databinding.ActivityCommonBinding
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.filesystem.mnt.RootMountPoint
import com.google.android.diskusage.utils.DeviceHelper
import com.google.android.diskusage.utils.IOHelper
import timber.log.Timber

/**
 * @brief 表示するストレージを選ぶ画面(ランチャーから起動する)。
 *
 * ストレージの一覧をダイアログで表示する。root 化した端末ではマウントポイントも選べる。
 */
class SelectActivity : ComponentActivity() {
    private var dialog: AlertDialog? = null

    /** @brief 表示したストレージごとの保存状態(戻ってきたときに復元する)。 */
    private val bundles = sortedMapOf<String, Bundle?>()
    private var expandRootMountPoints = false
    private val handler = Handler(Looper.getMainLooper())

    /** @brief メイン画面を起動し、閉じたときにその表示状態を受け取る。 */
    private val diskUsage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            val key = data.getStringExtra(DiskUsage.KEY_KEY) ?: return@registerForActivityResult
            bundles[key] = data.getBundleExtra(DiskUsage.STATE_KEY)
        }

    /** @brief 2 秒ごとに /proc/mounts を確認し、マウントが変わったら一覧を作り直す。 */
    private val checkForMountsUpdates = object : Runnable {
        override fun run() {
            val checksum = try {
                IOHelper.getProcMountsReader().useLines { lines -> lines.sumOf { it.length } }
            } catch (e: Exception) {
                RootMountPoint.checksum
            }
            if (checksum != RootMountPoint.checksum) {
                Timber.d("%s vs %s", checksum, RootMountPoint.checksum)
                MountPoint.reset()
                makeDialog()
            }
            handler.postDelayed(this, 2000)
        }
    }

    /**
     * @brief ストレージを表示する(権限の確認画面を経由してメイン画面を開く)。
     * @param mountPoint 表示するストレージ
     */
    private fun view(mountPoint: MountPoint) {
        val intent = Intent(this, PermissionRequestActivity::class.java)
            .putExtra(DiskUsage.KEY_KEY, mountPoint.key)
        bundles[mountPoint.key]?.let { intent.putExtra(DiskUsage.STATE_KEY, it) }
        diskUsage.launch(intent)
    }

    /** @brief マウントポイントの表示・非表示の設定画面を開く。 */
    private fun showHideMountPoints() {
        startActivity(Intent(this, ShowHideMountPointsActivity::class.java))
    }

    /**
     * @brief ストレージの選択ダイアログを作り直して表示する。
     *
     * root 化した端末では、最初は「root 権限が必要」の項目だけを出し、選ぶか非表示の設定が
     * あればマウントポイントを並べる。ダイアログを閉じると画面も閉じる。
     */
    private fun makeDialog() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        for (mountPoint in MountPoint.getMountPoints(this)) {
            options += mountPoint.title to { view(mountPoint) }
        }

        if (DeviceHelper.isDeviceRooted()) {
            val ignores = getSharedPreferences("ignore_list", Context.MODE_PRIVATE).all.keys
            if (ignores.isNotEmpty() || expandRootMountPoints) {
                for (mountPoint in RootMountPoint.getRootedMountPoints()) {
                    if (mountPoint.root in ignores) continue
                    options += mountPoint.root to { view(mountPoint) }
                }
                options += getString(R.string.show_hide_mount_points) to ::showHideMountPoints
            } else {
                options += getString(R.string.root_required) to {
                    expandRootMountPoints = true
                    makeDialog()
                }
            }
        }

        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setTitle(R.string.ask_view)
            .setOnCancelListener { finish() }
            .show()
    }

    /** @brief 表示用の文字列を準備し、空の画面を表示する。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
        setContentView(ActivityCommonBinding.inflate(layoutInflater).root)
    }

    /** @brief 選択ダイアログを表示し、マウントの監視を始める。 */
    override fun onResume() {
        super.onResume()
        makeDialog()
        handler.post(checkForMountsUpdates)
    }

    /** @brief ダイアログを閉じ、マウントの監視を止める。 */
    override fun onPause() {
        dialog?.dismiss()
        dialog = null
        handler.removeCallbacks(checkForMountsUpdates)
        super.onPause()
    }

    /** @brief ストレージごとの表示状態を保存する。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        for ((key, bundle) in bundles) {
            outState.putBundle(key, bundle)
        }
        outState.putStringArray(BUNDLE_KEYS, bundles.keys.toTypedArray())
    }

    /** @brief ストレージごとの表示状態を復元する。 */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        for (key in savedInstanceState.getStringArray(BUNDLE_KEYS).orEmpty()) {
            bundles[key] = savedInstanceState.getBundle(key)
        }
    }

    private companion object {
        const val BUNDLE_KEYS = "keys"
    }
}
