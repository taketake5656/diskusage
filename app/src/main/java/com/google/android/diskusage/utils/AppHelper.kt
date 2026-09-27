/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2022-2024 WhiredPlanck
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
 * @file AppHelper.kt
 * @brief アプリ全体で使う Context の取得。
 */
package com.google.android.diskusage.utils

import android.content.Context
import com.google.android.diskusage.DiskUsageApplication

/**
 * @brief アプリケーション Context を取り出すためのヘルパー。
 */
object AppHelper {

    /** @brief アプリケーション Context(Activity に依存しない処理で使う)。 */
    @JvmStatic
    val appContext: Context get() = DiskUsageApplication.getInstance().applicationContext
}