/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2026 taketake5656
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
 * @file WindowInsets.kt
 * @brief edge-to-edge 表示でシステムバーを避けるための拡張関数。
 */
package com.google.android.diskusage.utils

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * @brief コンテンツがシステムバーやディスプレイカットアウトに重ならないように余白を付ける。
 *
 * targetSdk 35 以降はウィンドウが常に edge-to-edge で配置され、
 * コンテンツがシステムバーの下にも描画されるため、その分の余白をコンテンツに設定する。
 */
fun Activity.applySystemBarsPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
