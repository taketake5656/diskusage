/**
 * @file PortableFile.kt
 * @brief ストレージのルートディレクトリを表すインターフェース。
 */
package com.google.android.diskusage.datasource

/**
 * @brief ストレージ(内部共有ストレージや SD カード)のルートディレクトリの情報。
 */
interface PortableFile {
    /** @brief 内部ストレージ上にエミュレートされた外部ストレージかどうか。 */
    val isExternalStorageEmulated: Boolean

    /** @brief 取り外し可能なストレージ(SD カードなど)かどうか。 */
    val isExternalStorageRemovable: Boolean

    /** @brief 正規化したパス。IOException のときは絶対パスで代用する。 */
    val canonicalPath: String

    /** @brief 絶対パス。 */
    val absolutePath: String

    /** @brief ストレージの総容量(バイト)。 */
    val totalSpace: Long
}
