/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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
 * @file FileInfoAdapter.kt
 * @brief 削除確認画面のファイル一覧のアダプター。
 */
package com.google.android.diskusage.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.diskusage.databinding.ListDirItemBinding
import com.google.android.diskusage.databinding.ListFileItemBinding

/**
 * @brief 削除確認画面のファイル一覧のアダプター。
 *
 * サイズが空の行はディレクトリ、それ以外はファイルとして別のレイアウトで表示する。
 *
 * @param infos 表示する行
 */
class FileInfoAdapter(private val infos: List<FileInfo>) :
    RecyclerView.Adapter<FileInfoAdapter.ViewHolder>() {
    /** @brief 1 行分のビュー。 @param ui 行のレイアウト */
    class ViewHolder(val ui: ViewBinding) : RecyclerView.ViewHolder(ui.root)

    /**
     * @brief 行のビューを作る。
     * @param parent 親のビュー
     * @param viewType 0 ならファイル、1 ならディレクトリ
     * @return 作成したビュー
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val ui = if (viewType == 0) {
            ListFileItemBinding.inflate(inflater)
        } else {
            ListDirItemBinding.inflate(inflater)
        }
        ui.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(ui)
    }

    /**
     * @brief 行に名前とサイズを表示する。
     * @param holder 行のビュー
     * @param position 行の位置
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fileInfo = infos[position]
        if (fileInfo.size.isEmpty()) {
            // directory
            val ui = holder.ui as ListDirItemBinding
            ui.name.text = fileInfo.name
        } else {
            val ui = holder.ui as ListFileItemBinding
            ui.name.text = fileInfo.name
            ui.size.text = fileInfo.size
        }
    }


    /** @brief 行数を返す。 */
    override fun getItemCount(): Int {
        return infos.size
    }

    /**
     * @brief 行の種類を返す。
     * @param position 行の位置
     * @return 0 ならファイル、1 ならディレクトリ
     */
    override fun getItemViewType(position: Int): Int {
        val fileInfo = infos[position]
        return if ((fileInfo.size.isEmpty())) 1 else 0
    }

    /** @brief 行の位置をそのまま ID にする。 */
    override fun getItemId(position: Int): Long {
        return position.toLong()
    }
}