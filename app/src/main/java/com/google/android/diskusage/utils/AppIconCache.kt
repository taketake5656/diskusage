/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2022 WhiredPlanck
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
 * @file AppIconCache.kt
 * @brief アプリのアイコンの読み込みとキャッシュ。
 */
package com.google.android.diskusage.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.widget.ImageView
import androidx.collection.LruCache
import com.google.android.diskusage.R
import kotlinx.coroutines.*
import me.zhanghai.android.appiconloader.AppIconLoader
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * @brief アプリのアイコンをバックグラウンドで読み込み、メモリ上にキャッシュする。
 *
 * キャッシュのキーは(パッケージ名, ユーザー ID, 大きさ)。
 */
object AppIconCache: CoroutineScope by MainScope() {
    /** @brief ビットマップの大きさ(KB)で容量を数える LRU キャッシュ。 */
    private class AppIconLruCache constructor(maxSize: Int) : LruCache<Triple<String, Int, Int>, Bitmap>(maxSize) {
        /** @brief ビットマップの大きさを KB で返す。 */
        override fun sizeOf(key: Triple<String, Int, Int>, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val lruCache: LruCache<Triple<String, Int, Int>, Bitmap>

    private val dispatcher: CoroutineDispatcher

    private var appIconLoaders = mutableMapOf<Int, AppIconLoader>()

    init {
        // Initialize app icon lru cache
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val availableCacheSize = (maxMemory / 4).toInt()
        lruCache = AppIconLruCache(availableCacheSize)

        // Initialize load icon scheduler
        val availableProcessorsCount = try {
            Runtime.getRuntime().availableProcessors()
        } catch (ignored: Exception) {
            1
        }
        val threadCount = 1.coerceAtLeast(availableProcessorsCount / 2)
        val loadIconExecutor: Executor = Executors.newFixedThreadPool(threadCount)
        dispatcher = loadIconExecutor.asCoroutineDispatcher()
    }

    /**
     * @brief アイコンの読み込みに使うディスパッチャーを返す。
     * @return CPU コア数の半分のスレッドを持つディスパッチャー
     */
    fun dispatcher(): CoroutineDispatcher {
        return dispatcher
    }

    /**
     * @brief キャッシュからアイコンを取り出す。
     * @param packageName パッケージ名
     * @param userId ユーザー ID
     * @param size アイコンの大きさ(ピクセル)
     * @return キャッシュされたアイコン。なければ null
     */
    private fun get(packageName: String, userId: Int, size: Int): Bitmap? {
        return lruCache[Triple(packageName, userId, size)]
    }

    /**
     * @brief アイコンをキャッシュに入れる(既にあれば何もしない)。
     * @param packageName パッケージ名
     * @param userId ユーザー ID
     * @param size アイコンの大きさ(ピクセル)
     * @param bitmap アイコン
     */
    private fun put(packageName: String, userId: Int, size: Int, bitmap: Bitmap) {
        if (get(packageName, userId, size) == null) {
            lruCache.put(Triple(packageName, userId, size), bitmap)
        }
    }

    /**
     * @brief アイコンをキャッシュから消す。
     * @param packageName パッケージ名
     * @param userId ユーザー ID
     * @param size アイコンの大きさ(ピクセル)
     */
    private fun remove(packageName: String, userId: Int, size: Int) {
        lruCache.remove(Triple(packageName, userId, size))
    }

    /**
     * @brief アイコンをキャッシュから取り出すか、なければ読み込む(処理が止まるのでワーカースレッドで呼ぶ)。
     * @param context Context
     * @param info アプリの情報
     * @param userId ユーザー ID
     * @param size アイコンの大きさ(ピクセル)
     * @return アイコン
     */
    @JvmStatic
    @SuppressLint("NewApi")
    fun getOrLoadBitmap(context: Context, info: ApplicationInfo, userId: Int, size: Int): Bitmap? {
        val cachedBitmap = get(info.packageName, userId, size)
        if (cachedBitmap != null) {
            return cachedBitmap
        }
        var loader = appIconLoaders[size]
        if (loader == null) {
            val shrinkNonAdaptiveIcons = Build.VERSION.SDK_INT >= 30 && context.applicationInfo.loadIcon(context.packageManager) is AdaptiveIconDrawable
            loader = AppIconLoader(size, shrinkNonAdaptiveIcons, context)
            appIconLoaders[size] = loader
        }
        val bitmap = loader.loadIcon(info, false)
        put(info.packageName, userId, size, bitmap)
        return bitmap
    }

    /**
     * @brief アイコンをバックグラウンドで読み込み、ImageView に表示する。
     *
     * 大きさはビューの幅(未確定なら既定の大きさ)。読み込めなければランチャーアイコン(API 26 以降)を表示する。
     *
     * @param context Context
     * @param info アプリの情報
     * @param userId ユーザー ID
     * @param view 表示先
     * @return 読み込みのジョブ(ビューを使い回すときに取り消せる)
     */
    @JvmStatic
    fun loadIconBitmapAsync(context: Context,
                            info: ApplicationInfo, userId: Int,
                            view: ImageView
    ): Job {
        return launch {
            val size = view.measuredWidth.let {
                if (it > 0) it
                else context.resources.getDimensionPixelSize(R.dimen.default_app_icon_size)
            }
            val cachedBitmap = get(info.packageName, userId, size)
            if (cachedBitmap != null) {
                view.setImageBitmap(cachedBitmap)
                return@launch
            }

            val bitmap = try {
                withContext(dispatcher) {
                    getOrLoadBitmap(context, info, userId, size)
                }
            } catch (e: CancellationException) {
                // do nothing if canceled
                return@launch
            } catch (e: Throwable) {
                null
            }

            if (bitmap != null) {
                view.setImageBitmap(bitmap)
            } else {
                if (Build.VERSION.SDK_INT >= 26) {
                    view.setImageResource(R.mipmap.ic_launcher)
                } else {
                    view.setImageDrawable(null)
                }
            }
        }
    }
}