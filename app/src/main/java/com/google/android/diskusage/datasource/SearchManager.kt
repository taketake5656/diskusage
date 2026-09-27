/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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
 * @file SearchManager.kt
 * @brief ファイル名検索をバックグラウンドで実行する。
 */
package com.google.android.diskusage.datasource

import com.google.android.diskusage.filesystem.entity.FileSystemEntry.SearchInterruptedException
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.DiskUsageMenu

/**
 * @brief ファイル名検索をバックグラウンドのスレッドで実行し、結果をメニューに返す。
 *
 * 入力が前回の検索語を含むときは前回の結果を絞り込むことで、入力のたびの検索を速くする。
 *
 * @param menu 検索結果を受け取るメニュー
 */
class SearchManager(private val menu: DiskUsageMenu) {
    private var finishedSearch: Search? = null
    private var activeSearch: Search? = null
    private lateinit var query: String

    /**
     * @brief 1 回分の検索を実行するスレッド。
     * @param query 検索語(小文字)
     * @param baseRoot 検索対象のツリー
     */
    private inner class Search(
        val query: String,
        var baseRoot: FileSystemSuperRoot
    ) : Thread() {
        /** @brief 検索結果のツリー。一致がなければ null。 */
        var newRoot: FileSystemSuperRoot? = null

        /** @brief ツリーを絞り込み、終わったらメインスレッドで searchFinished を呼ぶ。 */
        override fun run() {
            try {
                newRoot = baseRoot.filter(query, baseRoot.displayBlockSize) as FileSystemSuperRoot?
                if (isInterrupted) return
                menu.diskusage.handler.post { searchFinished(this@Search) }
            } catch (ignored: SearchInterruptedException) {
            }
        }
    }

    /**
     * @brief 検索語を更新して検索を始める。
     *
     * 実行中の検索語を含む入力なら、その検索の完了を待つ(完了後に最新の検索語でやり直す)。
     *
     * @param newQuery 新しい検索語
     */
    fun search(newQuery: String) {
        query = newQuery.lowercase()
        activeSearch?.let {
            if (newQuery.contains(it.query)) {
                return
            } else {
                it.interrupt()
                activeSearch = null
            }
        }
        startSearch()
    }

    /**
     * @brief 現在の検索語で検索スレッドを開始する。
     *
     * 前回の結果が使えればそれを対象に絞り込む。ツリーが無ければ空の結果を返す。
     */
    private fun startSearch() {
        var baseRoot = menu.masterRoot
        finishedSearch?.let {
            if (query.contains(it.query)) {
                baseRoot = it.newRoot
            } else {
                finishedSearch = null
            }
        }
        if (baseRoot != null) {
            val search = Search(query, baseRoot)
            activeSearch = search
            search.start()
        } else {
            menu.finishedSearch(null, null)
        }
    }

    /**
     * @brief 検索の完了を受け取る(メインスレッド)。
     *
     * 検索中に検索語が変わっていれば、続けて検索し直す。
     *
     * @param search 完了した検索
     */
    private fun searchFinished(search: Search) {
        if (activeSearch === search) {
            activeSearch = null
        }
        finishedSearch = search
        if (query != search.query) {
            startSearch()
        }
        menu.finishedSearch(search.newRoot, search.query)
    }

    /** @brief 実行中の検索を止め、前回の結果も破棄する。 */
    fun cancelSearch() {
        activeSearch?.interrupt()
        activeSearch = null
        finishedSearch = null
    }
}