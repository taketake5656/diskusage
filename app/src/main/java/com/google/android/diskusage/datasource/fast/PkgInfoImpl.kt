package com.google.android.diskusage.datasource.fast

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.google.android.diskusage.datasource.AppInfo
import com.google.android.diskusage.datasource.PkgInfo

class PkgInfoImpl(private val info: PackageInfo, private val pm: PackageManager) : PkgInfo {
    override val packageName: String
        get() = info.packageName
    override val applicationInfo: AppInfo
        get() = AppInfoImpl(requireNotNull(info.applicationInfo), pm)
}
