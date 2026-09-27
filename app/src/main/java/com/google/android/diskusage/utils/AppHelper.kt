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