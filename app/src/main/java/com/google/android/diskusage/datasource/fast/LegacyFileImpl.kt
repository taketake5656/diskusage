/**
 * @file LegacyFileImpl.kt
 * @brief java.io.File による LegacyFile の実装。
 */
package com.google.android.diskusage.datasource.fast

import com.google.android.diskusage.datasource.LegacyFile
import java.io.File
import java.io.IOException

/**
 * @brief java.io.File をそのまま使う LegacyFile の実装。
 * @param file 対象のファイル
 */
class LegacyFileImpl private constructor(private val file: File) : LegacyFile {
    /** @brief ファイル名。 */
    override val name: String
        get() = file.getName()

    /** @brief 正規化したパス。 */
    @get:Throws(IOException::class)
    override val canonicalPath: String
        get() = file.getCanonicalPath()

    /** @brief 正規化したパスが元のパスと異なる(または取得できない)ときにリンクとみなす。 */
    override val isLink: Boolean
        get() {
            try {
                if (file.getCanonicalPath() == file.path) return false
            } catch (ignored: Throwable) {
            }
            return true
        }

    /** @brief 通常のファイルかどうか。 */
    override val isFile: Boolean
        get() = file.isFile

    /** @brief ファイルのサイズ(バイト)を返す。 */
    override fun length(): Long {
        return file.length()
    }

    /** @brief ディレクトリ内の項目を返す。読めないときは null。 */
    override fun listFiles(): Array<LegacyFile>? {
        return file.listFiles()?.map { LegacyFileImpl(it) }?.toTypedArray()
    }

    /** @brief ディレクトリ内の項目の名前を返す。読めないときは null。 */
    override fun list(): Array<String>? {
        return file.list()
    }

    /**
     * @brief 名前を指定して子の項目を返す。
     * @param string 子の名前
     * @return 子の項目(存在しなくても作る)
     */
    override fun getChild(string: String): LegacyFile {
        return LegacyFileImpl(File(file, string))
    }

    companion object {
        /**
         * @brief スキャンのルートにする項目を作る。
         * @param root ルートのパス
         * @return ルートの項目
         */
        @JvmStatic
        fun createRoot(root: String): LegacyFile {
            return LegacyFileImpl(File(root))
        }
    }
}
