/**
 * @file PathHelper.kt
 * @brief ストレージのパスを取得するヘルパー。
 */
package com.google.android.diskusage.utils

import com.google.android.diskusage.DiskUsageApplication
import java.io.File

/**
 * @brief ストレージのパスを取得するヘルパー。
 */
object PathHelper {
    /**
     * @brief 各外部ストレージ上のアプリ専用ディレクトリの一覧を返す。
     *
     * 内部共有ストレージと SD カードなど、ストレージごとに 1 つずつ返る。
     * ストレージのルートを探す手がかりに使う。
     *
     * @return ストレージごとのアプリ専用ディレクトリ(取り外し中のストレージは null の場合がある)
     */
    @JvmStatic
    fun getExternalAppFilesPaths(): Array<out File> {
        return DiskUsageApplication.getInstance()
            .getExternalFilesDirs(null)
    }
}