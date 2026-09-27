/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2024 WhiredPlanck
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