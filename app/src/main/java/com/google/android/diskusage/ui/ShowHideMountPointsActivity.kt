package com.google.android.diskusage.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.diskusage.filesystem.FileSystemStats
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.RootMountPoint
import com.google.android.diskusage.utils.applySystemBarsPadding

/** Lets the user hide mount points of a rooted device. */
class ShowHideMountPointsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarsPadding()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, MountPointsFragment())
                .commit()
        }
    }

    class MountPointsFragment : PreferenceFragmentCompat() {
        private val ignoreList
            get() = requireContext().getSharedPreferences("ignore_list", Context.MODE_PRIVATE)

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
                title = "Setup visible mount points"
                isOrderingAsAdded = true
            }
        }

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
                    summary = FileSystemStats(mountPoint).formatUsageInfo()
                    isChecked = mountPoint.root !in ignores
                })
            }
        }

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
