/**
 * @file FileInfo.kt
 * @brief 削除確認画面の一覧に表示する 1 行分のデータ。
 */
package com.google.android.diskusage.ui.common

/**
 * @brief 削除確認画面の一覧の 1 行(サイズとファイル名)。
 *
 * @param size 表示用に整形したサイズ(ディレクトリは空文字列)
 * @param name ファイル名またはディレクトリ名
 */
data class FileInfo(val size: String, val name: String)