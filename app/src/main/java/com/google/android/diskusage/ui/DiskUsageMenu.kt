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
 * @file DiskUsageMenu.kt
 * @brief メイン画面のツールバーのメニュー(検索、表示、再スキャン、削除、このアプリについて)。
 */
package com.google.android.diskusage.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.view.forEach
import com.google.android.diskusage.R
import com.google.android.diskusage.databinding.AboutDialogBinding
import com.google.android.diskusage.datasource.SearchManager
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemSpecial
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.utils.AppIconCache.getOrLoadBitmap
import com.google.android.diskusage.utils.item
import splitties.resources.styledColor
import splitties.toast.toast
import timber.log.Timber

/**
 * @brief メイン画面のツールバーのメニューと検索を扱う。
 * @param diskusage メイン画面
 */
class DiskUsageMenu(val diskusage: DiskUsage) {
    /** @brief 検索で絞り込む前のツリー。 */
    var masterRoot: FileSystemSuperRoot? = null
    private var searchPattern: String? = null
    private val searchManager by lazy { SearchManager(this) }
    private var selectedEntity: FileSystemEntry? = null
    private var searchView: SearchView? = null
    private var origSearchBackground: Drawable? = null
    private lateinit var viewModel: DiskUsageViewModel

    /**
     * @brief ボタンの状態を持つ ViewModel を受け取る。
     * @param viewModel メイン画面の ViewModel
     */
    fun onCreate(viewModel: DiskUsageViewModel) {
        this.viewModel = viewModel
//        val actionBar = checkNotNull(diskusage.actionBar)
//        actionBar.setDisplayHomeAsUpEnabled(true)
    }

    /**
     * @brief 画面を閉じてよいかを返す。検索欄が開いていれば、先にそれを閉じる。
     * @return 閉じてよければ true
     */
    fun readyToFinish(): Boolean {
        val searchView = searchView
        if (searchView == null || searchView.isIconified) return true
        searchView.setQuery("", false)
        searchView.isIconified = true
        return false
    }

    /** @brief 検索キーが押されたときの処理(何もしない)。 */
    fun searchRequest() {
    }

    /**
     * @brief 検索欄のメニュー項目を追加する。入力のたびに検索し、閉じたら元のツリーに戻す。
     * @param menu 追加先のメニュー
     * @return 追加した項目
     */
    private fun setupSearchMenuItem(menu: Menu): MenuItem {
        val iconTint = diskusage.styledColor(android.R.attr.colorControlNormal)
        return menu.item(
            R.string.button_search,
            android.R.drawable.ic_search_category_default,
            iconTint,
            true
        ).apply {
            actionView = SearchView(diskusage).also {
                searchView = it
                origSearchBackground = it.background
                if (searchPattern != null) {
                    it.isIconified = false
                    it.setQuery(searchPattern, false)
                }
                it.setOnCloseListener {
                    Timber.d("Search process closed")
                    searchPattern = null
                    diskusage.applyPatternNewRoot(masterRoot)
                    false
                }
                it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String): Boolean {
                        onQueryTextChange(query)
                        return false
                    }

                    override fun onQueryTextChange(newText: String): Boolean {
                        Timber.d("Search query changed to: %s", newText)
                        searchPattern = newText
                        applyPattern(searchPattern)
                        return true
                    }
                })
            }
        }
    }

    /**
     * @brief 検索語を保存する。
     * @param outState 保存先
     */
    fun onSaveInstanceState(outState: Bundle) {
        outState.putString("search", searchPattern)
    }

    /**
     * @brief 検索語を復元する。
     * @param inState 保存された状態
     */
    fun onRestoreInstanceState(inState: Bundle) {
        searchPattern = inState.getString("search")
    }

    /**
     * @brief ツリーのビューを画面に設定し、メニューを更新する。
     * @param view 表示するビュー
     * @param newRoot 検索前のツリー
     */
    fun wrapAndSetContentView(view: View?, newRoot: FileSystemSuperRoot?) {
        masterRoot = newRoot
        updateMenu()
        diskusage.setContentView(view)
        diskusage.invalidateOptionsMenu()
    }

    /**
     * @brief 検索語でツリーを絞り込む。空なら元のツリーに戻す。
     * @param searchQuery 検索語
     */
    fun applyPattern(searchQuery: String?) {
        if (searchQuery == null || masterRoot == null) return

        if (searchQuery.isEmpty()) {
            searchManager.cancelSearch()
            finishedSearch(masterRoot, searchQuery)
        } else {
            searchManager.search(searchQuery)
        }
    }

    /**
     * @brief 検索結果を表示する。一致がなければ検索欄を赤くして元のツリーを表示する。
     * @param newRoot 絞り込んだツリー(一致がなければ null)
     * @param searchQuery 検索語
     * @return 一致があれば true
     */
    fun finishedSearch(newRoot: FileSystemSuperRoot?, searchQuery: String?): Boolean {
        return if (newRoot != null) {
            searchView?.background = origSearchBackground
            diskusage.applyPatternNewRoot(newRoot)
            true
        } else {
            searchView?.setBackgroundColor("#FFDDDD".toColorInt())
            diskusage.applyPatternNewRoot(masterRoot)
            false
        }
    }

    /**
     * @brief 選択中の項目が変わったときに、ボタンの状態を更新する。
     * @param position 選択中の項目
     */
    fun update(position: FileSystemEntry?) {
        this.selectedEntity = position
        updateMenu()
    }

    /**
     * @brief ツールバーのメニューを作る。
     *
     * 検索、表示、再スキャン、削除と「このアプリについて」を追加する。
     * 「このアプリについて」はバージョンとソースコードへのリンクをダイアログで表示し、
     * リンクをタップするとブラウザ(または GitHub アプリ)で開く。
     *
     * @param menu 追加先のメニュー
     */
    fun setupToolbarMenu(menu: Menu) {
        setupSearchMenuItem(menu)

        menu.item(R.string.button_show, showAsAction = true) {
            selectedEntity?.let { diskusage.view(it) }
        }.apply {
            viewModel.showButton.observe(diskusage) {
                isVisible = it
            }
        }

        menu.item(R.string.button_rescan) {
            diskusage.rescan()
        }.apply {
            viewModel.rescanButton.observe(diskusage) {
                isVisible = it
            }
        }

        menu.item(R.string.button_delete) {
            selectedEntity?.let { diskusage.askForDeletion(it) }
        }.apply {
            viewModel.deleteButton.observe(diskusage) {
                isVisible = it
            }
        }

        viewModel.toolbarActionButtonVisible.observe(diskusage) {
            menu.forEach { item -> item.isVisible = it }
        }

        menu.forEach { it.isVisible = false }

        menu.item(R.string.action_about) {
            val binding =
                AboutDialogBinding.inflate(
                    LayoutInflater.from(
                        diskusage
                    ), null, false
                )
            binding.sourceCode.text = HtmlCompat.fromHtml(
                diskusage.getString(
                    R.string.about_view_source_code,
                    "<b><a href=\"$SOURCE_CODE_URL\">GitHub</a></b>"
                ),
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            // Only styled as a link by the HTML, so open it in the browser on a tap
            binding.sourceCode.setOnClickListener {
                try {
                    diskusage.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri()))
                } catch (e: ActivityNotFoundException) {
                    Timber.w(e, "No browser to open %s", SOURCE_CODE_URL)
                    diskusage.toast(R.string.no_viewer_found)
                }
            }
            binding.icon.setImageBitmap(
                getOrLoadBitmap(
                    diskusage,
                    diskusage.applicationInfo,
                    Process.myUid() / 100000,
                    diskusage.resources.getDimensionPixelSize(R.dimen.default_app_icon_size)
                )
            )
            try {
                binding.versionName.text = diskusage.packageManager.getPackageInfo(
                    diskusage.packageName, 0
                ).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.e(e, "Package '${diskusage.packageName}' not found")
            }
            AlertDialog.Builder(diskusage)
                .setView(binding.root)
                .show()
        }
        updateMenu()
    }

    /**
     * @brief ボタンの表示と有効状態を更新する。
     *
     * 「表示」はルートと特別な項目以外で、「削除」はさらに削除できる項目・検索中でない
     * (またはファイル)・削除できるストレージのときに有効にする。
     */
    private fun updateMenu() {
        val state = diskusage.fileSystemState
        if (state == null) {
            viewModel.hideToolBarActionButton()
            return
        }

        viewModel.enableRescanButton()
        viewModel.showToolbarActionButton()

        val selected = selectedEntity
        val view = selected != null &&
            selected !== state.masterRoot.children?.get(0) &&
            selected !is FileSystemSpecial
        if (view) {
            viewModel.enableShowButton()
        } else {
            viewModel.disableShowButton()
        }

        val fileOrNotSearching = searchPattern == null || selected?.children == null
        val mountPoint = MountPoint.getForKey(diskusage, diskusage.key)
        if (view && selected.isDeletable && fileOrNotSearching &&
            mountPoint?.isDeleteSupported == true
        ) {
            viewModel.enableDeleteButton()
        } else {
            viewModel.disableDeleteButton()
        }
    }

    private companion object {
        /** @brief 「このアプリについて」に出すソースコードの URL。 */
        const val SOURCE_CODE_URL = "https://github.com/taketake5656/diskusage"
    }
}
