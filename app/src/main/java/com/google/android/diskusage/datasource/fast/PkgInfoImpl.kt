package com.google.android.diskusage.datasource.fast

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.google.android.diskusage.datasource.AppInfo
import com.google.android.diskusage.datasource.PkgInfo

class PkgInfoImpl(private val info: PackageInfo, private val pm: PackageManager) : PkgInfo {
    override val packageName: String
        get() = info.packageName
    override val applicationInfo: AppInfo?
        // Nullable since Android's own platform stubs mark PackageInfo.applicationInfo
        // as nullable (e.g. package queried with MATCH_UNINSTALLED_PACKAGES can lack one).
        get() = info.applicationInfo?.let { AppInfoImpl(it, pm) }
}
