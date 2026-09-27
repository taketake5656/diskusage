/**
 * @file ShowHideMountPointsActivity.kt
 * @brief root 化した端末で、選択画面に出すマウントポイントを選ぶ画面。
 */
package com.google.android.diskusage.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.FileSystemStats
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.RootMountPoint
import com.google.android.diskusage.utils.applySystemBarsPadding

/**
 * @brief root 化した端末のマウントポイントを、表示するかどうか利用者が選ぶ画面。
 */
class ShowHideMountPointsActivity : AppCompatActivity() {

    /** @brief マウントポイント一覧のフラグメントを表示する。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarsPadding()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, MountPointsFragment())
                .commit()
        }
    }

    /**
     * @brief マウントポイントごとのチェックボックスの一覧。
     *
     * チェックを外したマウントポイントは SharedPreferences `ignore_list` に記録する。
     */
    class MountPointsFragment : PreferenceFragmentCompat() {
        /** @brief 非表示にするマウントポイントを記録する SharedPreferences。 */
        private val ignoreList
            get() = requireContext().getSharedPreferences("ignore_list", Context.MODE_PRIVATE)

        /** @brief 空の設定画面を作る(項目は onResume で並べる)。 */
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
                title = getString(R.string.setup_visible_mount_points)
                isOrderingAsAdded = true
            }
        }

        /** @brief マウントポイントを読み直し、使用量と表示状態を付けて並べる。 */
        override fun onResume() {
            super.onResume()
            val context = requireContext()
            FileSystemEntry.setupStrings(context)
            val ignores = ignoreList.all.keys
            preferenceScreen.removeAll()
            for (mountPoint in RootMountPoint.getRootedMountPoints()) {
                preferenceScreen.addPreference(CheckBoxPreference(context).apply {
                    isPersistent = false
                    title = mountPoint.root
                    summary = FileSystemStats(mountPoint).formatUsageInfo(context)
                    isChecked = mountPoint.root !in ignores
                })
            }
        }

        /** @brief チェックを外したマウントポイントを保存する。 */
        override fun onPause() {
            super.onPause()
            ignoreList.edit {
                clear()
                for (i in 0 until preferenceScreen.preferenceCount) {
                    val pref = preferenceScreen.getPreference(i) as CheckBoxPreference
                    if (!pref.isChecked) {
                        putBoolean(pref.title.toString(), true)
                    }
                }
            }
        }
    }
}
