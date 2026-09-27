/**
 * @file DiskUsageViewModel.kt
 * @brief メイン画面のツールバーのボタンの状態。
 */
package com.google.android.diskusage.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * @brief メイン画面のツールバーのボタン(表示・再スキャン・削除)の表示と有効状態を持つ ViewModel。
 */
class DiskUsageViewModel : ViewModel() {
    /** @brief ツールバーのアクションボタンを表示するかどうか。 */
    val toolbarActionButtonVisible = MutableLiveData(false)

    /** @brief 「表示」ボタンが有効かどうか。 */
    val showButton = MutableLiveData(false)

    /** @brief 「再スキャン」ボタンが有効かどうか。 */
    val rescanButton = MutableLiveData(false)

    /** @brief 「削除」ボタンが有効かどうか。 */
    val deleteButton = MutableLiveData(false)

    /** @brief ツールバーのアクションボタンを表示する。 */
    fun showToolbarActionButton() {
        toolbarActionButtonVisible.value = true
    }

    /** @brief ツールバーのアクションボタンを隠す。 */
    fun hideToolBarActionButton() {
        toolbarActionButtonVisible.value = false
    }

    /** @brief 「表示」ボタンを有効にする。 */
    fun enableShowButton() {
        showButton.value = true
    }

    /** @brief 「表示」ボタンを無効にする。 */
    fun disableShowButton() {
        showButton.value = false
    }

    /** @brief 「再スキャン」ボタンを有効にする。 */
    fun enableRescanButton() {
        rescanButton.value = true
    }

    /** @brief 「再スキャン」ボタンを無効にする。 */
    fun disableRescanButton() {
        rescanButton.value = false
    }

    /** @brief 「削除」ボタンを有効にする。 */
    fun enableDeleteButton() {
        deleteButton.value = true
    }

    /** @brief 「削除」ボタンを無効にする。 */
    fun disableDeleteButton() {
        deleteButton.value = false
    }
}
