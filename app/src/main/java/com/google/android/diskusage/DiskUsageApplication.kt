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