/**
 * @file FileSystemRoot.kt
 * @brief スキャンしたディレクトリのルートを表す項目。
 */
package com.google.android.diskusage.filesystem.entity

/**
 * @brief スキャンしたディレクトリのルート。実際のパスを持ち、絶対パスから項目を探せる。
 */
class FileSystemRoot private constructor(
    name: String,
    /** @brief このルートの実際のパス。 */
    val rootPath: String,
    override val isDeletable: Boolean,
) : FileSystemEntry(null, name) {

    /**
     * @brief 同じ設定の空のルートを作る(コピー用)。
     * @return 新しいルート
     */
    override fun create(): FileSystemEntry = FileSystemRoot(name, rootPath, isDeletable)

    /**
     * @brief 子の項目だけを検索する(ルート自身の名前は照合しない)。
     * @param pattern 検索文字列
     * @param blockSize 表示のブロックサイズ
     * @return 一致した項目を含むツリー。なければ null
     */
    // don't match name
    override fun filter(pattern: CharSequence, blockSize: Long): FileSystemEntry? =
        filterChildren(pattern, blockSize)

    /**
     * @brief 絶対パスに対応する項目を探す。
     *
     * このルート配下でなければ、子に含まれる別のルートも探す。
     *
     * @param path 探す絶対パス
     * @return 見つかった項目。なければ null
     */
    fun getByAbsolutePath(path: String): FileSystemEntry? {
        val rootPathWithSlash = withSlash(rootPath)
        val pathWithSlash = withSlash(path)
        if (pathWithSlash == rootPathWithSlash) {
            return getEntryByName(path, true)
        }
        if (pathWithSlash.startsWith(rootPathWithSlash)) {
            return getEntryByName(path.substring(rootPathWithSlash.length), true)
        }
        return children!!.firstNotNullOfOrNull { (it as? FileSystemRoot)?.getByAbsolutePath(path) }
    }

    companion object {
        /**
         * @brief ルートの項目を作る。
         * @param name 表示名
         * @param rootPath 実際のパス
         * @param deletable 中の項目を削除できるかどうか
         * @return 作成したルート
         */
        fun makeNode(name: String, rootPath: String, deletable: Boolean) =
            FileSystemRoot(name, rootPath, deletable)

        /**
         * @brief パスの末尾に `/` を付ける(空文字列と既に付いているものはそのまま)。
         * @param path パス
         * @return 末尾が `/` のパス
         */
        private fun withSlash(path: String): String =
            if (path.isNotEmpty() && !path.endsWith('/')) "$path/" else path
    }
}
