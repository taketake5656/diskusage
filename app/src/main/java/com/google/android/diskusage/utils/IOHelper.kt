/**
 * @file IOHelper.kt
 * @brief システムファイルの読み込みヘルパー。
 */
package com.google.android.diskusage.utils

import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * @brief システムファイルの読み込みヘルパー。
 */
object IOHelper {
    private const val PROC_MOUNTS = "/proc/mounts"

    /**
     * @brief マウント一覧(/proc/mounts)を読むリーダーを開く。
     * @return 1 行 1 マウントのリーダー。呼び出し側で閉じること
     * @throws IOException 開けなかったとき
     */
    @JvmStatic
    @Throws(IOException::class)
    fun getProcMountsReader(): BufferedReader
        = BufferedReader(InputStreamReader(FileInputStream(PROC_MOUNTS)))

}