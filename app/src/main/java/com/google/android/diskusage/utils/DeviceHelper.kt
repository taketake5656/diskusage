/**
 * @file DeviceHelper.kt
 * @brief root 化の判定と、root 権限でコマンドを実行するための `su` の検出。
 */
package com.google.android.diskusage.utils

import timber.log.Timber
import java.io.File

/**
 * @brief 端末の状態を調べるヘルパー。
 */
object DeviceHelper {
    private const val SU = "su"
    private val suLocations = arrayOf(
        "/system/bin/", "/system/xbin/", "/sbin/", "/system/sd/xbin/",
        "/system/bin/failsafe/", "/data/local/xbin/", "/data/local/bin/", "/data/local/",
        "/system/sbin/", "/usr/bin/", "/vendor/bin/", "/product/bin/", "/debug_ramdisk/"
    )

    /**
     * @brief root 化されていそうかを、`su` の実行ファイルの有無で簡易に判定する。
     *
     * 画面に root 用の項目を出すかどうかの判断に使う。
     * `su` を起動しないので、メインスレッドから呼んでもよい。
     *
     * @return よく使われる場所か PATH のどこかに `su` があれば true
     */
    @JvmStatic
    fun isDeviceRooted(): Boolean {
        val pathDirs = System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }
        return (suLocations.asSequence() + pathDirs.asSequence())
            .any { location -> File(location, SU).exists() }
    }
}

/**
 * @brief 実際に起動して動く `su`(Magisk、KernelSU、APatch など)を探す。
 *
 * 起動すると root の許可ダイアログが出ることがあり、処理が止まるので、ワーカースレッドから呼ぶこと。
 */
object RootShell {
    // Prefer the master mount namespace so that the real /data/media, Android/data
    // and friends are visible even when the root manager isolates namespaces.
    private val candidates = listOf(
        listOf("su", "--mount-master"),
        listOf("su"),
        listOf("/system/bin/su"),
        listOf("/system/xbin/su"),
    )

    @Volatile
    private var working: List<String>? = null

    /**
     * @brief 動く `su` のコマンドを探す(見つかったものは覚えておく)。
     *
     * マウント名前空間を分けている root 管理アプリでも実際の /data/media などが見えるように、
     * `su --mount-master` を優先する。
     *
     * @return コマンドの前半(後ろに `-c <コマンド>` を付けて使う)。root が使えなければ null
     */
    @Synchronized
    @JvmStatic
    fun findSu(): List<String>? {
        working?.let { return it }
        for (candidate in candidates) {
            if (probe(candidate)) {
                Timber.d("RootShell: using %s", candidate)
                working = candidate
                return candidate
            }
        }
        return null
    }

    /**
     * @brief 候補のコマンドで `id` を実行し、root になれるかを確かめる。
     * @param candidate `su` のコマンド
     * @return 出力に `uid=0` があれば true
     */
    private fun probe(candidate: List<String>): Boolean = runCatching {
        val process = Runtime.getRuntime().exec((candidate + listOf("-c", "id")).toTypedArray())
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.contains("uid=0")
    }.getOrDefault(false)

    /**
     * @brief 文字列をシェルの単一引用符で囲む(中の `'` もエスケープする)。
     * @param s 引数にする文字列
     * @return シェルにそのまま渡せる文字列
     */
    @JvmStatic
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
