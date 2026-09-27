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
 * @file ScanProgressDialog.kt
 * @brief スキャンの進捗ダイアログ。
 */
package com.google.android.diskusage.ui.common

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import com.google.android.diskusage.R
import com.google.android.diskusage.databinding.ProgressBinding
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import java.text.NumberFormat

/**
 * @brief スキャンの進捗を、パーセンテージとスキャン中のパスで表示するダイアログ。
 *
 * スキャンは段階(ファイル → アプリ)に分かれ、それぞれが進捗バーの一部を受け持つ。
 * 表示する割合は段階の終わりを超えず、逆戻りもしない。
 *
 * @param context Context
 */
class ScanProgressDialog(context: Context) : AlertDialog(context) {
    private lateinit var binding: ProgressBinding
    private var details: CharSequence? = null
    private var progress = 0L
    private var max = 0L
    private val progressPercentFormat = NumberFormat.getPercentInstance().apply {
        maximumFractionDigits = 0
    }
    private var depth = 0
    private var warned = false
    private var prevPath = ""
    // The scan runs in phases (files, then apps), each filling a part of the bar
    private var phaseStart = 0.0
    private var phaseEnd = 1.0
    private var shownFraction = 0.0

    /**
     * @brief 現在の段階の進捗の最大値を設定する。
     * @param max 最大値(ブロック数やアプリの数)
     */
    fun setMax(max: Long) {
        this.max = max
    }

    /**
     * @brief 次の段階を始める。進捗バーの今の位置から end までをこの段階に割り当てる。
     *
     * 段階の進捗は 0 から数え直す。
     *
     * @param end 段階の終わりの割合(0.0〜1.0)
     */
    fun startPhase(end: Double) {
        phaseStart = shownFraction
        phaseEnd = end.coerceIn(phaseStart, 1.0)
        progress = 0
    }

    /**
     * @brief 項目のパス(ルートの名前を除く)を作り、深さを記録する。
     * @param entry 項目
     * @return `/` 区切りのパス。ルート直下より浅ければ空文字列
     */
    private fun path(entry: FileSystemEntry): String {
        val pathElements = generateSequence(entry) { it.parent }.map { it.name }.toList()
        depth = pathElements.size
        if (depth < 2) return ""
        return pathElements.dropLast(1).asReversed().joinToString("/")
    }

    /**
     * @brief パスを表示欄の幅に収まるように、途中を「...」で省略する。
     *
     * 前回のパスから変わった部分が見えるように残す。
     *
     * @param path パス
     * @return 表示するパス
     */
    private fun makePathString(path: String): String {
        if (!::binding.isInitialized) return path
        val prevPath = prevPath
        val len = minOf(path.length, prevPath.length)
        var diff = 0
        while (diff < len && path[diff] == prevPath[diff]) diff++

        val textPaint = binding.progressDetails.paint
        val winWidth = binding.progressDetails.width.toFloat()
        val extraTextWidth = textPaint.measureText("/.../G")
        val width = winWidth - extraTextWidth
        if (width < extraTextWidth) return path

        var firstSep = -2
        var lastSep = -2
        try {
            if (textPaint.measureText(path, 0, diff) < width) {
                this.prevPath = path
                return path
            }

            lastSep = path.lastIndexOf('/', diff)
            firstSep = path.indexOf('/')
            if (lastSep == -1 || firstSep == -1) return path

            var firstPart = textPaint.measureText(path, 0, firstSep)
            var lastPart = textPaint.measureText(path, lastSep, diff)
            if (firstPart + lastPart > width) {
                // need to break first and last string
                do {
                    if (firstPart > lastPart * 3) {
                        firstSep /= 2
                        firstPart = textPaint.measureText(path, 0, firstSep)
                    } else {
                        lastSep = (lastSep + diff) / 2
                        lastPart = textPaint.measureText(path, lastSep, diff)
                    }
                } while (firstPart + lastPart > width)

                this.prevPath = path
                return path.substring(0, firstSep) + "..." + path.substring(lastSep)
            }

            while (true) {
                var success = false

                val newLastSep = path.lastIndexOf('/', lastSep - 1)
                if (newLastSep != -1 && newLastSep >= firstSep) {
                    val newLastPart = textPaint.measureText(path, newLastSep, diff)
                    if (firstPart + newLastPart < width) {
                        success = true
                        lastPart = newLastPart
                        lastSep = newLastSep
                    }
                }

                val newFirstSep = path.indexOf('/', firstSep + 1)
                if (newFirstSep != -1 && newFirstSep <= lastSep) {
                    val newFirstPart = textPaint.measureText(path, 0, newFirstSep)
                    if (newFirstPart + lastPart < width) {
                        success = true
                        firstPart = newFirstPart
                        firstSep = newFirstSep
                    }
                }

                if (!success) {
                    this.prevPath = path
                    if (firstSep >= lastSep) {
                        return path
                    }
                    return path.substring(0, firstSep) + "/.../" + path.substring(lastSep + 1)
                }
            }
        } catch (e: RuntimeException) {
            throw RuntimeException(
                "path = $path[$firstSep:$lastSep] win =$winWidth extra=$extraTextWidth diff=$diff", e)
        }
    }

    /**
     * @brief 進捗バー、パーセンテージ、詳細の表示を更新する。
     *
     * 階層が 40 を超えたら、循環したディレクトリの可能性を警告する。
     */
    private fun onProgressChanged() {
        if (!::binding.isInitialized) return
        // Update the number and percent
        // The size to scan is only an estimate: files may be counted, which aren't
        // in the used blocks of the file system, so never pass the end of the phase
        // and never go back.
        val phaseFraction = if (max > 0) (progress.toDouble() / max).coerceIn(0.0, 1.0) else 0.0
        shownFraction = maxOf(shownFraction, phaseStart + (phaseEnd - phaseStart) * phaseFraction)
        val percent = shownFraction
        binding.progress.progress = (percent * 10000).toInt()
        binding.progressDetails.text = details
        binding.progressPercent.text = SpannableString(progressPercentFormat.format(percent)).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (depth > 40 && !warned) {
            warned = true
            setMessage(context.getString(R.string.cyclic_dirs_warning))
        }
    }

    /**
     * @brief ファイルのスキャンの進捗を設定する。
     * @param progress 現在の段階の進捗
     * @param entry 最後にスキャンした項目(パスを表示する)
     */
    fun setProgress(progress: Long, entry: FileSystemEntry) {
        this.progress = progress
        details = makePathString(path(entry))
        onProgressChanged()
    }

    /**
     * @brief 進捗と詳細の文字列を設定する(アプリの読み込みなど)。
     * @param progress 現在の段階の進捗
     * @param details 表示する詳細(アプリ名など)
     */
    fun setProgress(progress: Long, details: CharSequence) {
        this.progress = progress
        this.details = details
        onProgressChanged()
    }

    /** @brief 進捗の表示を作る(バーは 0〜10000 で表す)。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ProgressBinding.inflate(layoutInflater)
        binding.progress.max = 10000
        setView(binding.root)
        onProgressChanged()
        super.onCreate(savedInstanceState)
    }
}
