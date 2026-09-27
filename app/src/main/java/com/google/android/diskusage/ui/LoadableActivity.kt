/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 * Copyright (C) 2026 taketake5656
 *   2026: converted to Kotlin and modified (see the Git history)
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
 * @file LoadableActivity.kt
 * @brief ファイルシステムをバックグラウンドでスキャンする画面の基底クラス。
 */
package com.google.android.diskusage.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.common.ScanProgressDialog
import kotlin.concurrent.thread
import splitties.toast.toast
import timber.log.Timber

/** @brief スキャンしたツリーを受け取る処理。 */
fun interface AfterLoad {
    /**
     * @brief スキャンしたツリーを受け取る(メインスレッド)。
     * @param root ツリーのルート
     * @param isCached true なら前回のスキャン結果を使い回したもの
     */
    fun run(root: FileSystemSuperRoot, isCached: Boolean)
}

/**
 * @brief ファイルシステムをバックグラウンドでスキャンする画面の基底クラス。
 *
 * スキャンの結果と進行中のスキャンは、画面の再作成(回転など)をまたいで保たれる。
 */
abstract class LoadableActivity : AppCompatActivity() {
    /** @brief メインスレッドのハンドラ。 */
    val handler = Handler(Looper.getMainLooper())
    /** @brief 詳細設定を開いたアプリ(戻ったときにアンインストールされたかを確認する)。 */
    var pkgRemoved: FileSystemPackage? = null

    /** @brief 表示しているストレージの識別子(スキャン結果の保存先のキー)。 */
    abstract val key: String

    /**
     * @brief スキャンを実行する(バックグラウンドのスレッドで呼ばれる)。
     * @return ツリーのルート
     */
    protected abstract fun scan(): FileSystemSuperRoot

    /** @brief 画面の再作成をまたいで保つ、ストレージごとの状態。 */
    class PersistentActivityState {
        /** @brief 表示中の進捗ダイアログ。 */
        var loading: ScanProgressDialog? = null
        /** @brief スキャンしたツリー。 */
        var root: FileSystemSuperRoot? = null
        /** @brief スキャンの完了後に呼ぶ処理(スキャン中だけ設定される)。 */
        var afterLoad: AfterLoad? = null
    }

    /** @brief このストレージの状態。 */
    val persistentState: PersistentActivityState
        get() = persistentActivityStates.getOrPut(key) { PersistentActivityState() }

    /** @brief 表示用の文字列を準備する。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
    }

    /**
     * @brief ツリーを読み込む。前回の結果があればそれを使い、なければスキャンを始める。
     *
     * スキャン中は進捗ダイアログを表示し、取り消されたら画面を閉じる。
     * 既にスキャン中なら、ダイアログを出し直して完了後の処理だけを差し替える。
     * メモリ不足やエラーのときはダイアログで知らせる。
     *
     * @param afterLoad 読み込み後の処理
     * @param force true なら前回の結果を捨ててスキャンし直す
     */
    protected fun loadFiles(afterLoad: AfterLoad, force: Boolean) {
        val state = persistentState
        Timber.d("LoadableActivity.loadFiles(), afterLoad = %s", afterLoad)

        if (force) {
            state.root = null
        }
        state.root?.let {
            afterLoad.run(it, true)
            return
        }

        val scanRunning = state.afterLoad != null
        state.afterLoad = afterLoad
        Timber.d("loadFiles: Created new progress dialog")
        val loading = ScanProgressDialog(this).apply {
            setOnCancelListener {
                state.loading = null
                finish()
            }
            setCancelable(true)
            setMax(1)
            setMessage(getString(R.string.scaning_directories))
            show()
        }
        state.loading = loading

        if (scanRunning) return

        thread(name = "Scanner") {
            val error = try {
                Timber.d("loadFiles: Running scan for %s", key)
                val newRoot = scan()
                handler.post { onScanFinished(state, newRoot) }
                return@thread
            } catch (e: OutOfMemoryError) {
                state.root = null
                state.afterLoad = null
                Timber.d("loadFiles: Out of memory!")
                handler.post {
                    state.loading?.dismiss() ?: return@post
                    handleOutOfMemory()
                }
                return@thread
            } catch (e: StackOverflowError) {
                getString(R.string.filesystem_damaged)
            } catch (e: Exception) {
                Timber.e(e, "loadFiles: Native error")
                "${e.javaClass.name}:${e.message}"
            }
            state.root = null
            state.afterLoad = null
            Timber.d("loadFiles: Exception in scan!")
            handler.post {
                state.loading?.dismiss() ?: return@post
                AlertDialog.Builder(this)
                    .setTitle(error)
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    /**
     * @brief スキャンの完了を受け取る(メインスレッド)。
     *
     * ダイアログが既に閉じられていれば(画面が裏にある)結果だけ保存する。
     * ストレージが空なら再スキャンを促す。
     *
     * @param state このストレージの状態
     * @param newRoot スキャンしたツリー
     */
    private fun onScanFinished(state: PersistentActivityState, newRoot: FileSystemSuperRoot) {
        val loading = state.loading
        val afterLoad = state.afterLoad
        state.afterLoad = null
        if (loading == null) {
            Timber.d("loadFiles: No dialog, doesn't run afterLoad")
            if (newRoot.children!![0].children != null) {
                Timber.d("loadFiles: No dialog, updating root still")
                state.root = newRoot
            }
            return
        }
        if (loading.isShowing) loading.dismiss()
        state.loading = null
        Timber.d("loadFiles: Dismissed dialog")

        if (newRoot.children!![0].children == null) {
            Timber.d("loadFiles: Empty card")
            handleEmptySDCard(afterLoad!!)
            return
        }
        state.root = newRoot
        pkgRemoved = null
        Timber.d("loadFiles: Run afterLoad = %s", afterLoad)
        afterLoad!!.run(newRoot, false)
    }

    /** @brief メモリ不足をダイアログ(表示できなければトースト)で知らせる。 */
    private fun handleOutOfMemory() {
        try {
            // Can fail if the main window is already closed.
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.out_of_memory))
                .setOnCancelListener { finish() }
                .show()
        } catch (t: Throwable) {
            toast(R.string.out_of_memory)
        }
    }

    /** @brief 進捗ダイアログを閉じる(スキャンは続け、結果は保存される)。 */
    override fun onPause() {
        persistentState.loading?.let {
            if (it.isShowing) it.dismiss()
            Timber.d("onPause: Removed progress dialog")
            persistentState.loading = null
        }
        super.onPause()
    }

    /**
     * @brief ストレージが空か見つからないことを知らせ、再スキャンを選べるようにする。
     * @param afterLoad 再スキャン後の処理
     */
    private fun handleEmptySDCard(afterLoad: AfterLoad) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.empty_or_missing_sdcard))
            .setPositiveButton(getString(R.string.button_rescan)) { _, _ -> loadFiles(afterLoad, true) }
            .setOnCancelListener { finish() }
            .show()
    }

    private companion object {
        /** @brief ストレージの識別子ごとの状態(プロセスが生きている間保つ)。 */
        val persistentActivityStates = mutableMapOf<String, PersistentActivityState>()
    }
}
