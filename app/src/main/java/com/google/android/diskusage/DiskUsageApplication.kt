/*
 * DiskUsage - displays sdcard usage on android.
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
 * @file DiskUsageApplication.kt
 * @brief アプリケーションクラス。
 */
package com.google.android.diskusage

import android.app.Application
import timber.log.Timber

/**
 * @brief アプリケーションクラス。インスタンスの保持とデバッグログの設定を行う。
 */
class DiskUsageApplication: Application() {
    companion object {
        private var instance: DiskUsageApplication? = null

        /**
         * @brief アプリケーションのインスタンスを返す。
         * @return インスタンス
         * @throws IllegalStateException onCreate より前に呼んだとき
         */
        fun getInstance() = instance
            ?: throw IllegalStateException("DiskUsage application is not created!")

    }

    /**
     * @brief インスタンスを保持し、デバッグ版では Timber のログ出力を有効にする。
     *
     * ログのタグにはスレッド名、メッセージの先頭にはファイル名と行番号を付ける。
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(
                    object : Timber.DebugTree() {
                        override fun createStackElementTag(element: StackTraceElement): String =
                            "${super.createStackElementTag(element)}|${element.fileName}:${element.lineNumber}"

                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            super.log(
                                priority,
                                "[${Thread.currentThread().name}] ${tag?.substringBefore('|')}",
                                "${tag?.substringAfter('|')}] $message",
                                t,
                            )
                        }
                    },
                )
            }
        } catch (e: Exception) {
            e.fillInStackTrace()
            return
        }
    }
}