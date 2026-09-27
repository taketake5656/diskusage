/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
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
 * @file FileSystemState.kt
 * @brief ツリー表示の状態(カーソル、拡大縮小、アニメーション、入力)。
 */
package com.google.android.diskusage.ui

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemFreeSpace
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.filesystem.entity.FileSystemSystemSpace
import kotlin.math.abs
import splitties.toast.toast
import timber.log.Timber

/**
 * @brief ファイルシステムの表示状態。カーソル、拡大縮小、アニメーション、タッチ操作を扱う。
 *
 * 縦方向はツリーの座標(描画単位、FileSystemEntry.sizeForRendering)で表示範囲を持ち、
 * 横方向は表示範囲の左端の深さで持つ。目標の範囲に向けてアニメーションする。
 * メインスレッドだけで扱う。
 *
 * @param context メイン画面
 * @param root 表示するツリー
 */
class FileSystemState(
    private val context: DiskUsage,
    root: FileSystemSuperRoot,
) {
    private lateinit var view: FileSystemView
    /** @brief 表示しているツリー。 */
    var masterRoot: FileSystemSuperRoot = root
        private set
    private lateinit var cursor: Cursor

    private var numSpecialEntries = 0
    private var freeSpace: FileSystemFreeSpace? = null
    private var systemSpace: FileSystemSystemSpace? = null
    private var freeSpaceZoom = 0L

    private var targetViewDepth = 0f
    private var targetViewTop = 0L
    private var targetViewBottom = 0L
    private var targetElementWidth = 0

    private var prevViewDepth = 0f
    private var prevViewTop = 0L
    private var prevViewBottom = 0L
    private var prevElementWidth = 0

    private var viewDepth = 0f
    private var viewTop = 0L
    private var viewBottom = 0L

    private var displayTop = 0L
    private var displayBottom = 0L

    // Safe values to not crash when touch events come before screen initialized.
    private var screenWidth = 400
    private var screenHeight = 400

    private var yscale = 0f

    private var animationStartTime = 0L
    private val interpolator = DecelerateInterpolator()
    private var maxLevels = 3.2f

    private var fullZoom = false
    private var warnOnFileSelect = false

    private var touchDepth = 0f
    private var touchPoint = 0L

    private var touchEntry: FileSystemEntry? = null
    private var touchX = 0f
    private var touchY = 0f
    private var touchMovement = false
    private var speedX = 0f
    private var speedY = 0f
    private var prevMoveTime = 0L

    private var touchZoom = 0L
    private var multiNumTouches = 0
    private var multitouchReset = false
    private var touchWidth = 0f
    private var touchPointX = 0f
    private var minDistance = 0f
    private var minDistanceX = 0f
    private var minElementWidth = 0
    private var maxElementWidth = 0

    private var screenTouching = false

    private var deletingEntry: FileSystemEntry? = null
    private var deletingAnimationStartTime = 0L
    private var deletingInitialSize = 0L

    /**
     * @brief 拡大の状態。
     *
     * ZOOM_FULL は空き容量を含む全体、ZOOM_ALLOCATED は使用済みの部分、ZOOM_OTHER はそれ以外。
     */
    private enum class ZoomState {
        ZOOM_FULL,
        ZOOM_ALLOCATED,
        ZOOM_OTHER
    }

    private var zoomState = ZoomState.ZOOM_ALLOCATED

    init {
        targetViewBottom = root.sizeForRendering
        updateSpecialEntries()
        resetCursor()
    }

    /** @brief 複数の指によるピンチ操作(縦横の拡大縮小と移動)を扱う。 */
    inner class MultiTouchHandler {
        private val filterX = ArrayList<MotionFilter>()
        private val filterY = ArrayList<MotionFilter>()

        /**
         * @brief i 本目の指の x 座標のフィルタを返す(なければ作る)。
         * @param i 指の番号
         * @return フィルタ
         */
        private fun getFilterX(i: Int): MotionFilter {
            if (filterX.size <= i) filterX.add(MotionFilter())
            return filterX[i]
        }

        /**
         * @brief i 本目の指の y 座標のフィルタを返す(なければ作る)。
         * @param i 指の番号
         * @return フィルタ
         */
        private fun getFilterY(i: Int): MotionFilter {
            if (filterY.size <= i) filterY.add(MotionFilter())
            return filterY[i]
        }

        /**
         * @brief タッチイベントの値をコピーする。
         * @param ev タッチイベント
         * @return コピー
         */
        fun newMyMotionEvent(ev: MotionEvent) = MyMotionEvent(ev)

        /**
         * @brief 複数の指によるタッチを処理する。
         *
         * 指の間隔で表示範囲の高さと項目の幅を、指の中心で位置を決める。
         *
         * @param ev タッチイベント
         * @return 複数の指のイベントとして処理したら true(1 本なら false)
         */
        internal fun handleTouch(ev: MyMotionEvent): Boolean {
            val action = ev.action
            val num = ev.pointerCount
            if (num == 1) {
                return false
            }

            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN) {
                multitouchReset = true
                for (i in 0 until num) {
                    getFilterX(i).noFilter(ev.getX(i))
                    getFilterY(i).noFilter(ev.getY(i))
                }
            }

            if (action != MotionEvent.ACTION_MOVE) {
                return true
            }

            var xmin = getFilterX(0).doFilter(ev.getX(0))
            var xmax = xmin
            var ymin = getFilterY(0).doFilter(ev.getY(0))
            var ymax = ymin
            for (i in 1 until num) {
                val x = getFilterX(i).doFilter(ev.getX(i))
                val y = getFilterY(i).doFilter(ev.getY(i))
                if (x < xmin) xmin = x
                if (x > xmax) xmax = x
                if (y < ymin) ymin = y
                if (y > ymax) ymax = y
            }
            val dy = (ymax - ymin).coerceAtLeast(minDistance)
            val avgY = 0.5f * (ymax + ymin)
            val avgX = 0.5f * (xmax + xmin)
            if (multitouchReset) {
                multitouchReset = false
                multiNumTouches = num
                touchMovement = true
                touchZoom = (displayBottom - displayTop) * dy.toLong() / screenHeight
                touchPoint = displayTop + (displayBottom - displayTop) * avgY.toLong() / screenHeight

                minDistanceX = FileSystemEntry.elementWidth / 2f
                val dx = (xmax - xmin).coerceAtLeast(minDistanceX)
                touchWidth = dx / FileSystemEntry.elementWidth
                touchPointX = viewDepth + avgX / FileSystemEntry.elementWidth
                return true
            }
            val displayHeight = touchZoom * screenHeight / dy.toLong()
            displayTop = touchPoint - displayHeight * avgY.toLong() / screenHeight
            displayBottom = displayTop + displayHeight

            val dx = (xmax - xmin).coerceAtLeast(minDistanceX)
            FileSystemEntry.elementWidth = (dx / touchWidth).toInt().coerceAtLeast(minElementWidth)
            targetElementWidth = FileSystemEntry.elementWidth

            viewDepth = touchPointX - avgX / FileSystemEntry.elementWidth
            targetViewDepth = viewDepth
            maxLevels = screenWidth / FileSystemEntry.elementWidth.toFloat()

            val dt = (displayBottom - displayTop) / 41
            if (dt < 2) {
                displayBottom += 41 * 2
            }
            viewTop = displayTop + dt
            viewBottom = displayBottom - dt

            targetViewTop = viewTop
            targetViewBottom = viewBottom
            animationStartTime = 0
            requestRepaint()
            return true
        }
    }

    /** @brief 複数の指によるタッチの処理。 */
    val multitouchHandler = MultiTouchHandler()

    /**
     * @brief 1 本の指のドラッグで表示範囲を動かし、フリング用の速度を記録する。
     *
     * 少し動くまではタップとみなして動かさない。画面の 60% までははみ出して動かせる。
     *
     * @param newTouchX 指の x 座標
     * @param newTouchY 指の y 座標
     * @param moveTime イベントの時刻
     */
    private fun onMotion(newTouchX: Float, newTouchY: Float, moveTime: Long) {
        val touchOffsetX = newTouchX - touchX
        val touchOffsetY = newTouchY - touchY
        speedX += touchOffsetX
        speedY += touchOffsetY
        val dt = moveTime - prevMoveTime
        if (dt > 10) {
            speedX *= 10f / dt
            speedY *= 10f / dt
            prevMoveTime = moveTime - 10
        }

        if (abs(touchOffsetX) < 10 && abs(touchOffsetY) < 10 && !touchMovement) return
        touchMovement = true

        viewDepth -= touchOffsetX / FileSystemEntry.elementWidth
        if (viewDepth * FileSystemEntry.elementWidth < -screenWidth * 0.6) {
            viewDepth = -screenWidth * 0.6f / FileSystemEntry.elementWidth
        }
        targetViewDepth = viewDepth

        val offset = (touchOffsetY / yscale).toLong()
        val allowedOverflow = (screenHeight * 0.6f / yscale).toLong()
        viewTop -= offset
        viewBottom -= offset

        if (viewTop < -allowedOverflow) {
            val oldTop = viewTop
            viewTop = -allowedOverflow
            viewBottom += viewTop - oldTop
        }

        if (viewBottom > masterRoot.sizeForRendering + allowedOverflow) {
            val oldBottom = viewBottom
            viewBottom = masterRoot.sizeForRendering + allowedOverflow
            viewTop += viewBottom - oldBottom
        }

        targetViewTop = viewTop
        targetViewBottom = viewBottom
        animationStartTime = 0
        touchX = newTouchX
        touchY = newTouchY
        requestRepaint()
    }

    /** @brief 指の小さな揺れを取り除くフィルタ。 */
    class MotionFilter {
        private var cur = 0f
        private var cur2 = 0f
        private var dx2 = 0f

        /**
         * @brief フィルタをリセットし、値をそのまま返す。
         * @param value 座標
         * @return value
         */
        fun noFilter(value: Float): Float {
            cur = value
            cur2 = value
            dx2 = 0f
            return value
        }

        /**
         * @brief 値にフィルタをかける。dx 以内の揺れは無視し、動き続けるほど追従を速くする。
         * @param value 座標
         * @return フィルタ後の座標
         */
        fun doFilter(value: Float): Float {
            if (value > cur + dx) {
                cur += value - (cur + dx)
                dx2 = (dx2 - 1).coerceAtLeast(0f)
            } else if (value < cur - dx) {
                cur += value - (cur - dx)
                dx2 = (dx2 - 1).coerceAtLeast(0f)
            } else {
                dx2 = (dx2 + 1).coerceAtMost(dx)
            }
            if (value > cur2 + dx2) {
                cur2 += value - (cur2 + dx2)
            } else if (value < cur2 - dx2) {
                cur2 += value - (cur2 - dx2)
            }
            return cur2
        }

        companion object {
            /** @brief 無視する揺れの幅(画面の大きさから決める)。 */
            var dx = 5f
        }
    }

    private val filterX = MotionFilter()
    private val filterY = MotionFilter()

    /**
     * @brief MotionEvent の値のコピー(元のイベントは再利用されるため)。
     * @param ev 元のイベント
     */
    class MyMotionEvent(ev: MotionEvent) {
        val eventTime: Long = ev.eventTime
        val x: Float = ev.x
        val y: Float = ev.y
        val action: Int = ev.action
        val pointerCount: Int = ev.pointerCount
        private val xx = FloatArray(pointerCount) { ev.getX(it) }
        private val yy = FloatArray(pointerCount) { ev.getY(it) }

        /** @brief i 本目の指の x 座標を返す。 */
        fun getX(i: Int) = xx[i]
        /** @brief i 本目の指の y 座標を返す。 */
        fun getY(i: Int) = yy[i]
    }

    /**
     * @brief タッチイベントを処理する。
     *
     * タップは項目の選択、ドラッグは移動、離したときはフリングする。
     * 削除のアニメーション中は無視する。
     *
     * @param ev タッチイベント
     */
    fun onTouchEvent(ev: MyMotionEvent) {
        if (sdcardIsEmpty()) return

        if (deletingEntry != null) {
            // setup state of multitouch to reinitialize next time
            multiNumTouches = 0
            return
        }

        if (multitouchHandler.handleTouch(ev)) return

        val action = ev.action
        if (multiNumTouches > 1) {
            if (action == MotionEvent.ACTION_UP) {
                multiNumTouches = 0
                screenTouching = false
                requestRepaint()
            }
            return
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                screenTouching = true
                multiNumTouches = 1
                multitouchReset = true
                touchX = filterX.noFilter(ev.x)
                touchY = filterY.noFilter(ev.y)
                touchDepth = (FileSystemEntry.elementWidth * viewDepth + touchX) /
                    FileSystemEntry.elementWidth
                touchPoint = displayTop + (displayBottom - displayTop) * touchY.toLong() / screenHeight
                touchEntry = masterRoot.findEntry(touchDepth.toInt() + 1, touchPoint).takeIf {
                    if (it === masterRoot) Timber.d("warning: masterRoot selected in onTouchEvent")
                    it !== masterRoot
                }
                speedX = 0f
                speedY = 0f
                prevMoveTime = ev.eventTime
            }

            MotionEvent.ACTION_MOVE -> {
                onMotion(filterX.doFilter(ev.x), filterY.doFilter(ev.y), ev.eventTime)
            }

            MotionEvent.ACTION_UP -> {
                screenTouching = false
                filterX.doFilter(ev.x)
                filterY.doFilter(ev.y)

                if (!touchMovement) {
                    val touchEntry = touchEntry
                    if (touchEntry == null) {
                        Timber.d("touchEntry == null")
                        return
                    }
                    if (masterRoot.depth(touchEntry) > touchDepth.toInt() + 1) return
                    touchSelect(touchEntry, ev.eventTime)
                    return
                }
                touchMovement = false
                fling()

                if (animationStartTime != 0L) return
                prepareMotion(ev.eventTime)
                animationDuration = 300
                requestRepaint()
            }
        }
    }

    /** @brief 指を離したときの速度に応じて、目標の表示範囲を先へ動かす。 */
    private fun fling() {
        val touchOffsetX = speedX * 15
        val touchOffsetY = speedY * 15
        targetViewDepth -= touchOffsetX / FileSystemEntry.elementWidth
        if (targetViewDepth * FileSystemEntry.elementWidth < -screenWidth * 0.6) {
            targetViewDepth = -screenWidth * 0.6f / FileSystemEntry.elementWidth
        }

        val offset = (touchOffsetY / yscale).toLong()
        val allowedOverflow = (screenHeight * 0.6f / yscale).toLong()
        targetViewTop -= offset
        targetViewBottom -= offset

        if (targetViewTop < -allowedOverflow) {
            val oldTop = targetViewTop
            targetViewTop = -allowedOverflow
            targetViewBottom += targetViewTop - oldTop
        }

        if (targetViewBottom > masterRoot.sizeForRendering + allowedOverflow) {
            val oldBottom = targetViewBottom
            targetViewBottom = masterRoot.sizeForRendering + allowedOverflow
            targetViewTop += targetViewBottom - oldBottom
        }
    }

    /** @brief カーソルを表示上のルートの最初の子に戻し、タッチの状態をリセットする。 */
    fun resetCursor() {
        // FIXME: dirty hacks
        cursor = Cursor(this, masterRoot)
        touchEntry = null
        touchMovement = false
    }

    /**
     * @brief 新しいツリーに切り替え、カーソルを作り直す。
     * @param newRoot 新しいツリー
     */
    private fun rescanFinished(newRoot: FileSystemSuperRoot) {
        masterRoot = newRoot
        updateSpecialEntries()
        cursor = Cursor(this, masterRoot)
        requestRepaint()
    }

    /**
     * @brief ツリーを差し替える(検索の絞り込みなど)。
     *
     * カーソルは同じパスの項目(なければその祖先)に置き、画面上の見え方がなるべく変わらないようにする。
     *
     * @param newRoot 新しいツリー
     */
    fun replaceRootKeepCursor(newRoot: FileSystemSuperRoot) {
        var oldPosition = cursor.position
        val newPosition = newRoot.getEntryByName(oldPosition.path2(), false)
            ?: newRoot.children!![0]
        val newDepth = newRoot.depth(newPosition)
        var oldDepth = masterRoot.depth(cursor.position)
        while (oldDepth > newDepth) {
            oldPosition = oldPosition.parent!!
            oldDepth--
        }
        val oldTop = masterRoot.getOffset(oldPosition)
        val oldSize = oldPosition.sizeForRendering
        val oldBottom = oldTop + oldSize
        val newTop = newRoot.getOffset(newPosition)
        val newSize = newPosition.sizeForRendering
        val newBottom = newTop + newSize
        val above = (oldTop - targetViewTop) / oldSize.toDouble()
        val below = (targetViewBottom - oldBottom) / oldSize.toDouble()
        prepareMotion(SystemClock.uptimeMillis())
        viewTop = newTop - (above * newSize).toLong()
        viewBottom = (below * newSize).toLong() + newBottom
        targetViewTop = viewTop.coerceAtMost(newTop)
        targetViewBottom = viewBottom.coerceAtLeast(newBottom)
        animationDuration = 300
        rescanFinished(newRoot)
        cursor.set(this, newPosition)
    }

    /**
     * @brief 読み込み直後の表示を始める。
     * @param newRoot 新しいツリー(null なら今のまま)
     * @param animate true なら全体が縮んで収まるアニメーションをする
     */
    fun startZoomAnimation(newRoot: FileSystemSuperRoot?, animate: Boolean) {
        if (newRoot != null) rescanFinished(newRoot)
        if (animate) {
            val large = masterRoot.sizeForRendering * 10
            val center = masterRoot.sizeForRendering / 2
            viewTop = center - large
            viewBottom = center + large
            viewDepth = 0f
            prepareMotion(SystemClock.uptimeMillis())
            animationDuration = 300
            targetViewTop = 0
            targetViewBottom = masterRoot.sizeForRendering
            targetViewDepth = 0f
            zoomState = ZoomState.ZOOM_ALLOCATED
            setZoomState()
        }
        requestRepaint()
    }

    /**
     * @brief 描画先のビューを設定する。
     * @param view ビュー
     */
    fun setView(view: FileSystemView) {
        this.view = view
    }

    /**
     * @brief カーソルの移動をメイン画面に知らせる(タイトルとメニューの更新)。
     * @param position 選択中の項目
     */
    internal fun onCursorMoved(position: FileSystemEntry) {
        context.setSelectedEntity(position)
    }

    /** @brief 表示上のルートの中から、空き容量とシステムの領域の項目を探し直す。 */
    private fun updateSpecialEntries() {
        numSpecialEntries = 0
        freeSpace = null
        systemSpace = null
        freeSpaceZoom = 0
        val entries = masterRoot.children?.get(0)?.children ?: return
        for (e in entries) {
            if (e is FileSystemSystemSpace) {
                systemSpace = e
                numSpecialEntries++
            }
            if (e is FileSystemFreeSpace) {
                freeSpace = e
                numSpecialEntries++
            }
        }
    }

    /**
     * @brief 描画の前に、アニメーションの進み具合から今の表示範囲と倍率を計算する。
     * @return アニメーション中なら true
     */
    private fun preDraw(): Boolean {
        fadeAwayEntry()

        var animation = deletingEntry != null
        val curr = SystemClock.uptimeMillis()
        if (curr > animationStartTime + animationDuration) {
            // no animation
            viewTop = targetViewTop
            viewBottom = targetViewBottom
            viewDepth = targetViewDepth
            FileSystemEntry.elementWidth = targetElementWidth
            maxLevels = screenWidth / targetElementWidth.toFloat()
        } else {
            val f = interpolator.getInterpolation(
                (curr - animationStartTime) / animationDuration.toFloat()).toDouble()
            viewTop = (f * targetViewTop + (1 - f) * prevViewTop).toLong()
            viewBottom = (f * targetViewBottom + (1 - f) * prevViewBottom).toLong()
            viewDepth = (f * targetViewDepth + (1 - f) * prevViewDepth).toFloat()
            FileSystemEntry.elementWidth = (f * targetElementWidth + (1 - f) * prevElementWidth).toInt()
            animation = true
        }

        val dt = (viewBottom - viewTop) / 40
        displayTop = viewTop - dt
        displayBottom = viewBottom + dt

        yscale = screenHeight / (displayBottom - displayTop).toFloat()
        return animation
    }

    /**
     * @brief 描画の後に、表示範囲がツリーの外にはみ出していれば戻すアニメーションを始める。
     * @param animation アニメーション中かどうか
     * @return 続けて再描画が必要なら true
     */
    private fun postDraw(animation: Boolean): Boolean {
        if (animation) {
            return true
        }
        if (screenTouching) {
            return false
        }
        val rootSize = masterRoot.sizeForRendering
        if (targetViewTop >= 0 && targetViewBottom <= rootSize &&
            viewDepth >= 0 && FileSystemEntry.elementWidth <= maxElementWidth
        ) {
            return false
        }
        prepareMotion(SystemClock.uptimeMillis())
        animationDuration = 300
        if (targetViewTop < 0) {
            val oldTop = targetViewTop
            targetViewTop = 0
            targetViewBottom += targetViewTop - oldTop
        } else if (targetViewBottom > rootSize) {
            val oldBottom = targetViewBottom
            targetViewBottom = rootSize
            targetViewTop += targetViewBottom - oldBottom
        }
        targetViewTop = targetViewTop.coerceAtLeast(0)
        targetViewBottom = targetViewBottom.coerceAtMost(rootSize)
        if (viewDepth < 0) {
            targetViewDepth = 0f
        }
        targetElementWidth = targetElementWidth.coerceAtMost(maxElementWidth)
        return true
    }

    private val drawBounds = Rect()

    /**
     * @brief ツリーを描画する。アニメーション中は再描画を続ける。
     * @param canvas 描画先
     * @param skin 見た目
     */
    fun onDraw(canvas: Canvas, skin: TreeSkin) {
        try {
            val animation = preDraw()
            val bounds = drawBounds
            canvas.getClipBounds(bounds)
            if (bounds.left == 0 && bounds.top == 0 && bounds.right == 0 && bounds.bottom == 0) {
                bounds.set(0, 0, screenWidth, screenHeight)
            }
            masterRoot.paint(canvas, skin, bounds, cursor, displayTop, viewDepth, yscale,
                screenHeight, numSpecialEntries)
            if (postDraw(animation)) {
                requestRepaint()
            }
        } catch (t: Throwable) {
            Timber.d(t, "onDraw: Got exception")
        }
    }

    /**
     * @brief 今の表示範囲をアニメーションの開始点として記録する。
     * @param time アニメーションの開始時刻
     */
    fun prepareMotion(time: Long) {
        animationDuration = 900
        prevViewDepth = viewDepth
        prevViewTop = viewTop
        prevViewBottom = viewBottom
        prevElementWidth = FileSystemEntry.elementWidth
        animationStartTime = time
    }

    /**
     * @brief タップした項目を選択し、見やすいように拡大する。
     *
     * 表示上のルートか空き容量なら全体と使用済みの部分の表示を切り替える。
     * ディレクトリは、同じ項目をもう一度タップすると画面いっぱいに拡大する。
     *
     * @param entry タップした項目
     * @param eventTime イベントの時刻
     */
    private fun touchSelect(entry: FileSystemEntry, eventTime: Long) {
        val prevCursor = cursor.position
        val prevDepth = cursor.depth
        cursor.set(this, entry)
        val currDepth = cursor.depth
        prepareMotion(eventTime)

        if (entry === masterRoot.children!![0] || entry is FileSystemFreeSpace) {
            toggleZoomState()
            return
        }

        zoomState = ZoomState.ZOOM_OTHER

        zoomFitLabelMoveUp(eventTime)
        zoomFitToScreen(eventTime)
        val hasChildren = !entry.children.isNullOrEmpty()
        if (!hasChildren) {
            fullZoom = false
            if (targetViewTop == prevViewTop && targetViewBottom == prevViewBottom &&
                !warnOnFileSelect && entry !is FileSystemSystemSpace
            ) {
                toast(R.string.warn_on_file_select)
                warnOnFileSelect = true
            }
            val minRequiredDepth = cursor.depth + 1 - maxLevels
            if (targetViewDepth < minRequiredDepth) {
                targetViewDepth = minRequiredDepth
            }
            return
        }
        fullZoom = when {
            prevCursor === entry -> !fullZoom
            currDepth < prevDepth -> false
            else -> entry.sizeForRendering * yscale > FileSystemEntry.fontSize * 2
        }

        var maxRequiredDepth = (cursor.depth - if (cursor.depth > 0) 1 else 0).toFloat()
        var minRequiredDepth = cursor.depth + 2 - maxLevels
        if (minRequiredDepth > maxRequiredDepth) {
            if (fullZoom) {
                maxRequiredDepth = minRequiredDepth
            } else {
                minRequiredDepth = maxRequiredDepth
            }
        }

        if (targetViewDepth < minRequiredDepth) {
            targetViewDepth = minRequiredDepth
        } else if (targetViewDepth > maxRequiredDepth) {
            targetViewDepth = maxRequiredDepth
        }
        if (fullZoom) {
            targetViewTop = cursor.top
            targetViewBottom = cursor.top + cursor.position.sizeForRendering
        }
        if (targetViewBottom == prevViewBottom && targetViewTop == prevViewTop) {
            fullZoom = false
            targetViewTop = cursor.top + 1
            targetViewBottom = cursor.top + cursor.position.sizeForRendering - 1
            zoomFitLabelMoveUp(eventTime)
            zoomFitToScreen(eventTime)
        }
        val freeSpaceClip = getFreeSpaceZoom()
        if (targetViewBottom > freeSpaceClip) {
            targetViewBottom = freeSpaceClip
            if (targetViewTop == 0L) zoomState = ZoomState.ZOOM_ALLOCATED
        }
    }

    /**
     * @brief 選択中の項目にラベルが入るように拡大する。
     * @param eventTime イベントの時刻
     */
    private fun zoomFitLabel(eventTime: Long) {
        val positionSize = cursor.position.sizeForRendering
        if (positionSize == 0L) return

        val yscale = screenHeight / (targetViewBottom - targetViewTop).toFloat()
        if (positionSize * yscale > FileSystemEntry.fontSize * 2 + 2) {
            // position large enough to contain label
            return
        }
        // zoom in
        val newYscale = FileSystemEntry.fontSize * 2.5f / positionSize
        prepareMotion(eventTime)

        targetViewTop = targetViewBottom - (screenHeight / newYscale).toLong()

        if (targetViewTop > cursor.top) {
            // moving down to fit view after zoom in, 10% from top
            val offset = cursor.top - (targetViewTop * 0.8 + targetViewBottom * 0.2).toLong()
            targetViewTop += offset
            targetViewBottom += offset

            if (targetViewTop < 0) {
                targetViewBottom -= targetViewTop
                targetViewTop = 0
            }
        }
    }

    /**
     * @brief 選択中の項目にラベルが入るように拡大し、項目が見えるように上へ動かす。
     * @param eventTime イベントの時刻
     */
    private fun zoomFitLabelMoveUp(eventTime: Long) {
        val positionSize = cursor.position.sizeForRendering
        if (positionSize == 0L) return

        zoomFitLabel(eventTime)

        if (targetViewBottom < cursor.top + positionSize) {
            // move up as needed
            prepareMotion(eventTime)

            val offset = cursor.top + positionSize - (targetViewTop * 0.2 + targetViewBottom * 0.8).toLong()
            targetViewTop += offset
            targetViewBottom += offset
            val rootSize = masterRoot.sizeForRendering
            if (targetViewBottom > rootSize) {
                val diff = targetViewBottom - rootSize
                targetViewBottom = rootSize
                targetViewTop -= diff
            }
        }
        requestRepaint()
    }

    /**
     * @brief 選択中の項目が画面に収まっていなければ、親全体が見えるように縮小する。
     * @param eventTime イベントの時刻
     */
    private fun zoomFitToScreen(eventTime: Long) {
        if (targetViewTop < cursor.top &&
            targetViewBottom > cursor.top + cursor.position.sizeForRendering
        ) {
            // fits in, no need for zoom out
            return
        }

        prepareMotion(eventTime)

        val viewRoot = cursor.position.parent!!
        targetViewTop = masterRoot.getOffset(viewRoot)
        targetViewBottom = targetViewTop + viewRoot.sizeForRendering
        zoomFitLabelMoveUp(eventTime)
        requestRepaint()
    }

    /**
     * @brief カーソルを親に移す。
     * @param eventTime イベントの時刻
     * @return 移動したら true(既に最上位なら false)
     */
    private fun back(eventTime: Long): Boolean {
        val newPosition = cursor.position.parent
        if (newPosition == null || newPosition === masterRoot) {
            return false
        }
        cursor.set(this, newPosition)

        if (newPosition === masterRoot.children?.get(0)) {
            prepareMotion(eventTime)
            zoomState = ZoomState.ZOOM_FULL
            setZoomState()
            return true
        }

        val requiredDepth = cursor.depth - if (cursor.position.parent === masterRoot) 0 else 1
        if (targetViewDepth > requiredDepth) {
            prepareMotion(eventTime)
            targetViewDepth = requiredDepth.toFloat()
        }
        zoomFitToScreen(eventTime)
        return true
    }

    /**
     * @brief カーソルが項目の上にあれば、前の兄弟か親に移す(削除の前に呼ぶ)。
     * @param entry 削除する項目
     */
    private fun moveAwayCursor(entry: FileSystemEntry) {
        if (cursor.position !== entry) return
        cursor.up(this)
        if (cursor.position !== entry) return
        cursor.left(this)
    }

    /**
     * @brief 項目をアニメーションしながらツリーから取り除く。
     * @param entry 取り除く項目
     */
    fun removeEntry(entry: FileSystemEntry) {
        fadeAwayEntryStart(entry)
        requestRepaint()
    }

    /**
     * @brief 削除中の項目をツリーから取り除き、その分を空き容量に加える。
     *
     * 並べ直すときは、特別な項目が末尾に残るようにする。
     */
    private fun deleteDeletingEntry() {
        val deletingEntry = deletingEntry!!
        if (deletingEntry.parent === masterRoot) {
            throw IllegalStateException("sdcard deletion is not available in UI")
        }
        val displayBlockSize = masterRoot.displayBlockSize
        moveAwayCursor(deletingEntry)
        deletingEntry.remove(displayBlockSize)
        val deletingEntryBlocks = deletingEntry.sizeInBlocks
        val root = masterRoot.children!![0]
        freeSpace?.let { freeSpace ->
            freeSpace.setSizeInBlocks(freeSpace.sizeInBlocks + deletingEntryBlocks, displayBlockSize)
            masterRoot.setSizeInBlocks(masterRoot.sizeInBlocks + deletingEntryBlocks, displayBlockSize)
            root.setSizeInBlocks(root.sizeInBlocks + deletingEntryBlocks, displayBlockSize)
            freeSpace.clearSizeStringCache()
        }

        FileSystemEntry.deletedEntry = null

        // Keep special entries last while sorting, otherwise painting code works incorrect
        val freeSpaceEncoded = freeSpace?.encodedSize
        val systemSpaceEncoded = systemSpace?.encodedSize
        freeSpace?.encodedSize = -2
        systemSpace?.encodedSize = -1
        var parent = deletingEntry.parent
        while (parent != null) {
            parent.children!!.sortWith(FileSystemEntry.COMPARE)
            parent = parent.parent
        }
        root.children!!.forEach { Timber.d("entry = %s %s", it.name, it.sizeInBlocks) }
        if (freeSpaceEncoded != null) freeSpace?.encodedSize = freeSpaceEncoded
        if (systemSpaceEncoded != null) systemSpace?.encodedSize = systemSpaceEncoded

        this.deletingEntry = null
        cursor.set(this, cursor.position)
    }

    /**
     * @brief 項目が縮んで消えるアニメーションを始める(前の削除が残っていれば先に終わらせる)。
     * @param entry 削除する項目
     */
    private fun fadeAwayEntryStart(entry: FileSystemEntry) {
        if (deletingEntry != null) {
            deleteDeletingEntry()
        }
        deletingAnimationStartTime = 0
        deletingEntry = entry
        FileSystemEntry.deletedEntry = entry
        deletingInitialSize = entry.sizeInBlocks
    }

    /** @brief 再描画を要求する。 */
    fun requestRepaint() {
        view.invalidate()
    }

    /**
     * @brief 削除のアニメーションを 1 コマ進める。
     *
     * 項目のサイズを時間とともに減らし、その分を祖先から引いて空き容量に加える。
     * 子は収まる分だけ残して切り詰める。時間が過ぎたら取り除く。
     */
    private fun fadeAwayEntry() {
        var entry = deletingEntry ?: return

        val time = SystemClock.uptimeMillis()
        if (deletingAnimationStartTime == 0L) {
            deletingAnimationStartTime = time
        }
        val dt = time - deletingAnimationStartTime
        if (dt > DELETION_ANIMATION_DURATION) {
            deleteDeletingEntry()
            return
        }
        requestRepaint()
        val f = interpolator.getInterpolation(dt / animationDuration.toFloat())
        val prevSize = entry.sizeInBlocks
        var newBlocks = ((1 - f) * deletingInitialSize).toLong()
        val dSize = newBlocks - prevSize

        if (dSize >= 0) return

        val displayBlockSize = masterRoot.displayBlockSize
        var parent = entry.parent
        while (parent != null) {
            parent.setSizeInBlocks(parent.sizeInBlocks + dSize, displayBlockSize)
            parent = parent.parent
        }
        freeSpace?.let { freeSpace ->
            val root = masterRoot.children!![0]
            masterRoot.setSizeInBlocks(masterRoot.sizeInBlocks - dSize, displayBlockSize)
            root.setSizeInBlocks(root.sizeInBlocks - dSize, displayBlockSize)
            freeSpace.setSizeInBlocks(freeSpace.sizeInBlocks - dSize, displayBlockSize)
        }
        // truncate children
        while (true) {
            val deltaBlocks = newBlocks - entry.sizeInBlocks
            if (deltaBlocks == 0L) return
            entry.setSizeInBlocks(entry.sizeInBlocks + deltaBlocks, displayBlockSize)
            val children = entry.children
            if (children.isNullOrEmpty()) return
            var blocks = 0L
            val prevEntry = entry
            for (i in children.indices) {
                blocks += children[i].sizeInBlocks
                // if sum of sizes of children less then newSize continue
                if (newBlocks > blocks) continue

                // size of children larger than newSize, need to trunc last child
                val lastChildSizeChange = blocks - newBlocks
                // size of last child will be updated at the begining of while loop
                newBlocks = children[i].sizeInBlocks - lastChildSizeChange
                entry.children = children.sliceArray(0..i)
                entry = children[i]
                break
            }
            if (prevEntry === entry) {
                // Entry was truncated, but not its children
                break
            }
        }
    }

    /** @brief 削除のアニメーションを終わらせる(削除に失敗して項目を戻すときなど)。 */
    fun restore() {
        if (deletingEntry != null) {
            deleteDeletingEntry()
        }
        requestRepaint()
    }

    /**
     * @brief ストレージが空か(カーソルを置ける項目がないか)を返す。
     * @return 空なら true
     */
    fun sdcardIsEmpty(): Boolean = cursor.position === masterRoot

    /**
     * @brief キー操作を処理する。
     *
     * 方向キーでカーソルを動かし、決定キーで項目を開く。検索キーと戻るキーはメイン画面に渡す。
     *
     * @param keyCode キーコード
     * @param event キーイベント
     * @return 処理したら true
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (sdcardIsEmpty()) return false
        when (keyCode) {
            KeyEvent.KEYCODE_SEARCH -> {
                context.searchRequest()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                context.finishOnBack()
                return true
            }
        }

        if (deletingEntry != null) {
            return keyCode in NAVIGATION_KEYS
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                cursor.down(this)
                zoomFitLabelMoveUp(event.eventTime)
                zoomFitToScreen(event.eventTime)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                cursor.up(this)
                zoomFitLabel(event.eventTime)
                zoomFitToScreen(event.eventTime)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                back(event.eventTime)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cursor.right(this)
                zoomFitLabelMoveUp(event.eventTime)

                val requiredDepth = cursor.depth + 1 +
                    (if (cursor.position.children == null) 0 else 1) - maxLevels
                if (viewDepth < requiredDepth) {
                    prepareMotion(event.eventTime)
                    targetViewDepth = requiredDepth
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                val selected = cursor.position
                // FIXME: hack to disable removal of /sdcard
                if (selected !== masterRoot.children!![0]) {
                    context.view(selected)
                }
                return true
            }
        }
        return false
    }

    /**
     * @brief 画面の大きさから、項目の幅と操作のしきい値を決める。
     * @param width 幅
     * @param height 高さ
     */
    // FIXME: can be called from different thread
    fun layout(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        minElementWidth = screenWidth / 8
        maxElementWidth = screenWidth / 2
        // FIXME: may be too large
        MotionFilter.dx = (screenHeight + screenWidth) / 50f

        minDistance = maxOf(screenHeight, screenWidth) / 10f
        Timber.d("Screen = %s x %s", screenWidth, screenHeight)
        targetElementWidth = (screenWidth / maxLevels).toInt()
        FileSystemEntry.elementWidth = targetElementWidth
        setZoomState()
    }

    /**
     * @brief カーソルの位置と表示範囲を復元する。
     * @param inState saveState で保存した状態
     */
    fun restoreState(inState: Bundle) {
        val cursorName = inState.getString("cursor") ?: return
        val entry = masterRoot.getEntryByName(cursorName, true) ?: return
        cursor.set(this, entry)
        viewDepth = inState.getFloat("viewDepth")
        prevViewDepth = viewDepth
        targetViewDepth = viewDepth
        viewTop = inState.getLong("viewTop")
        prevViewTop = viewTop
        targetViewTop = viewTop
        viewBottom = inState.getLong("viewBottom")
        prevViewBottom = viewBottom
        targetViewBottom = viewBottom
        zoomState = when (inState.getInt("zoomState")) {
            0 -> ZoomState.ZOOM_ALLOCATED
            1 -> ZoomState.ZOOM_FULL
            else -> ZoomState.ZOOM_OTHER
        }
        maxLevels = inState.getFloat("maxLevels")
        requestRepaint()
    }

    /**
     * @brief カーソルの位置と表示範囲を保存する。
     * @param outState 保存先
     */
    fun saveState(outState: Bundle) {
        outState.putString("cursor", cursor.position.path2())
        outState.putFloat("viewDepth", viewDepth)
        outState.putLong("viewTop", viewTop)
        outState.putLong("viewBottom", viewBottom)
        outState.putFloat("maxLevels", maxLevels)
        outState.putInt("zoomState", when (zoomState) {
            ZoomState.ZOOM_ALLOCATED -> 0
            ZoomState.ZOOM_FULL -> 1
            ZoomState.ZOOM_OTHER -> 2
        })
    }

    /**
     * @brief 使用済みの部分を表示するときの、表示範囲の下端を返す。
     *
     * 空き容量のラベルが入るだけの高さを残す。
     *
     * @return 表示範囲の下端(描画単位)
     */
    private fun getFreeSpaceZoom(): Long {
        if (freeSpaceZoom != 0L) return freeSpaceZoom
        val freeSpace = freeSpace ?: return masterRoot.sizeForRendering

        freeSpaceZoom = masterRoot.sizeForRendering
        val busy = masterRoot.sizeForRendering - freeSpace.sizeForRendering
        val message = FileSystemEntry.fontSize * 2 + 1f
        val height = screenHeight / 41f * 40f
        var required = (busy * (height / (height - message))).toLong()
        required = (required * (40f / 40.5f)).toLong()
        if (required < freeSpaceZoom * 0.9f) {
            freeSpaceZoom = required
        }
        return freeSpaceZoom
    }

    /** @brief 拡大の状態に合わせて、目標の表示範囲を設定する。 */
    private fun setZoomState() {
        if (screenHeight == 0) return
        when (zoomState) {
            ZoomState.ZOOM_ALLOCATED -> {
                targetViewDepth = 0f
                targetViewTop = 0
                targetViewBottom = getFreeSpaceZoom()
            }
            ZoomState.ZOOM_FULL -> {
                targetViewDepth = 0f
                targetViewTop = 0
                targetViewBottom = masterRoot.sizeForRendering
            }
            ZoomState.ZOOM_OTHER -> {}
        }
    }

    /** @brief 全体と使用済みの部分の表示を切り替える。 */
    private fun toggleZoomState() {
        zoomState = if (zoomState == ZoomState.ZOOM_ALLOCATED) {
            ZoomState.ZOOM_FULL
        } else {
            ZoomState.ZOOM_ALLOCATED
        }
        setZoomState()
    }

    private companion object {
        /** @brief 表示範囲のアニメーションの長さ(ミリ秒)。 */
        var animationDuration = 900L
        /** @brief 削除のアニメーションの長さ(ミリ秒)。 */
        const val DELETION_ANIMATION_DURATION = 900L
        /** @brief 削除のアニメーション中に無視する方向キーと決定キー。 */
        val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
    }
}
