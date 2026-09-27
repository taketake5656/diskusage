/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2022 WhiredPlanck
 * Copyright (C) 2026 taketake5656
 *   2026: modified (see the Git history)
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
 * @file DeviceInfo.kt
 * @brief 不具合報告用の端末情報の文字列を作る。
 */
package com.google.android.diskusage.utils

import android.content.Context
import android.content.res.Configuration
import com.google.android.diskusage.BuildConfig

/**
 * @brief アプリと端末の情報をまとめるヘルパー。
 *
 * https://gist.github.com/hendrawd/01f215fd332d84793e600e7f82fc154b を元にしている。
 */
// Adapted from https://gist.github.com/hendrawd/01f215fd332d84793e600e7f82fc154b
object DeviceInfo {
    /**
     * @brief アプリのバージョン、OS、機種、画面の情報を複数行の文字列にする。
     * @param context 画面の情報の取得に使う Context
     * @return 1 行 1 項目の文字列
     */
    fun get(context: Context) =
        buildString {
            appendLine("App Package Name: ${BuildConfig.APPLICATION_ID}")
            appendLine("App Version Name: ${BuildConfig.VERSION_NAME}")
            appendLine("App Version Code: ${BuildConfig.VERSION_CODE}")
            appendLine("OS Name: ${android.os.Build.DISPLAY}")
            appendLine("OS Version: ${System.getProperty("os.version")} (${android.os.Build.VERSION.INCREMENTAL})")
            appendLine("OS API Level: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Device: ${android.os.Build.DEVICE}")
            appendLine("Model (product): ${android.os.Build.MODEL} (${android.os.Build.PRODUCT})")
            appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
            appendLine("Tags: ${android.os.Build.TAGS}")
            val metrics = context.resources.displayMetrics
            appendLine("Screen Size: ${metrics.widthPixels} x ${metrics.heightPixels}")
            appendLine("Screen Density: ${metrics.density}")
            appendLine(
                "Screen orientation: ${
                    when (context.resources.configuration.orientation) {
                        Configuration.ORIENTATION_PORTRAIT -> "Portrait"
                        Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
                        Configuration.ORIENTATION_UNDEFINED -> "Undefined"
                        else -> "Unknown"
                    }
                }"
            )
        }
}
