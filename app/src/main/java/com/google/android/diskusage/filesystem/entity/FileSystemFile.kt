/**
 * @file FileSystemFile.kt
 * @brief ツリー上の通常のファイルを表す項目。
 */
package com.google.android.diskusage.filesystem.entity

/**
 * @brief ツリー上の通常のファイル(子を持たない項目)。
 */
class FileSystemFile private constructor(parent: FileSystemEntry?, name: String) :
    FileSystemEntry(parent, name) {

    /** @brief ファイルは常に削除できる。 */
    override val isDeletable: Boolean
        get() = true

    /**
     * @brief 同じ名前の空の項目を作る(コピー用)。
     * @return 親を持たない新しい項目
     */
    override fun create(): FileSystemEntry = FileSystemFile(null, name)

    companion object {
        /**
         * @brief ファイルの項目を作る。
         * @param parent 親ディレクトリの項目
         * @param name ファイル名
         * @return 作成した項目
         */
        fun makeNode(parent: FileSystemEntry?, name: String): FileSystemEntry =
            FileSystemFile(parent, name)
    }
}
