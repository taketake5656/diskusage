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
